package me.cference.artemis.search

import me.cference.artemis.domain.Tag
import me.cference.artemis.projection.{PostRow, ReadModelRepository}

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneOffset}
import scala.util.Try

/** A compiled, parameterized query: the SQL (`$1..$n` placeholders) and its ordered bind params. */
final case class CompiledQuery(sql: String, params: Seq[SqlParam])

/**
 * Compiles a merged [[QueryPlan]] into ONE parameterized SQL query against the `posts` read table
 * (search-dsl tasks 4.1-4.3). The heart of the DSL:
 *
 *   - 4.1 tag core: `includes` → `tags @> ?` (GIN), `excludes` → `NOT (tags && ?)`, each OR group →
 *     `(tags && ?)` (an EMPTY positive group → the literal `FALSE`), plus a default `status <>
 *     'deleted'` unless a `status:` predicate overrides it.
 *   - 4.2 metatag catalog: every column-backed metatag → a predicate fragment + params; `Exclude`
 *     polarity wraps it in `NOT (...)`; catalog entries the read model cannot back are deferred
 *     with a typed [[CompileError.UnsupportedMetatag]].
 *   - 4.3 ordering: `ORDER BY <col> DESC, id DESC` with an `id` tiebreak, keyset (row-value)
 *     `WHERE` from a [[Cursor]] (never `OFFSET`), a stable seeded `md5` shuffle for `order:random`,
 *     and a page-size clamp to [[MaxPageSize]].
 *
 * Every user value is a bind param — nothing is interpolated (injection-safe). Total: all failure
 * modes are `Left(CompileError)`.
 *
 * NOTE: `id` is a `VARCHAR`, so `id:` comparisons/ranges are lexicographic string comparisons
 * (`id:100..200` compares `"100"`..`"200"` as text), not numeric — the read-model id-string caveat.
 */
object SqlCompiler:

  /**
   * Result page ceiling (search-dsl "Query guardrails"): the effective limit is clamped to this.
   */
  val MaxPageSize: Int = 200

  private val Columns = ReadModelRepository.PostColumns

  /** The clamped page size actually used, given the plan's `limit:` and the request's page size. */
  def effectiveLimit(plan: QueryPlan, pageSize: Int): Int =
    math.min(math.max(plan.limit.getOrElse(pageSize), 1), MaxPageSize)

  /** The normalized order name (lowercased/trimmed) the plan sorts by; `date` is the default. */
  def normalizeOrder(plan: QueryPlan): String =
    plan.order.map(_.trim.toLowerCase).filter(_.nonEmpty).getOrElse("date")

  /**
   * The order-column value of `row` as a cursor string, or `None` when the id (and seed) alone key
   * the page (`id`, `random`). Used by the executor to build the next-page cursor.
   */
  def orderKeyOf(order: String, row: PostRow): Option[String] = order match
    case "score" => Some(row.score.toString)
    case "favcount" => Some(row.favCount.toString)
    case "duration" => Some(row.duration.getOrElse(0L).toString)
    case "mpixels" =>
      Some((row.width.getOrElse(0).toLong * row.height.getOrElse(0).toLong).toString)
    case "date" => Some(row.createdAt.toString)
    case _ => None // id, random

  // --- compile ------------------------------------------------------------

  def compile(
      plan: QueryPlan,
      cursor: Option[Cursor],
      pageSize: Int,
      seed: Option[String]
  ): Either[CompileError, CompiledQuery] =
    val order = normalizeOrder(plan)
    for
      _ <- validateOrder(order)
      predFrags <- traverse(plan.predicates)(compileMetaTerm)
      keyset <- keysetCondition(order, cursor, seed)
      orderBy <- orderByClause(order, seed)
    yield
      val where = tagConditions(plan) ++ statusDefault(plan) ++ predFrags ++ keyset.toSeq
      val whereSql = where.map(_._1).mkString(" AND ")
      val whereParams = where.flatMap(_._2)
      val limit = effectiveLimit(plan, pageSize)
      val templated =
        s"SELECT $Columns FROM posts WHERE $whereSql ORDER BY ${orderBy._1} LIMIT ?"
      val params = whereParams ++ orderBy._2 :+ SqlParam.IntP(limit)
      CompiledQuery(numberPlaceholders(templated), params)

  // --- 4.1 tag conditions -------------------------------------------------

  private type Frag = (String, Seq[SqlParam])

  private def tagConditions(plan: QueryPlan): Seq[Frag] =
    val inc =
      if plan.includes.nonEmpty then Seq(("tags @> ?", Seq(arrayOf(plan.includes)))) else Nil
    val exc =
      if plan.excludes.nonEmpty then Seq(("NOT (tags && ?)", Seq(arrayOf(plan.excludes)))) else Nil
    val ors = plan.orGroups.map { g =>
      if g.isEmpty then ("FALSE", Seq.empty[SqlParam]) else ("(tags && ?)", Seq(arrayOf(g)))
    }
    inc ++ exc ++ ors

  private def arrayOf(tags: Set[Tag]): SqlParam =
    SqlParam.TextArray(tags.toSeq.map(_.value).sorted)

  /** Exclude soft-deleted rows by default, unless the query carries its own `status:` predicate. */
  private def statusDefault(plan: QueryPlan): Seq[Frag] =
    if plan.predicates.exists(_.name.toLowerCase == "status") then Nil
    else Seq(("status <> 'deleted'", Seq.empty))

  // --- 4.2 metatag catalog ------------------------------------------------

  private def compileMetaTerm(mt: MetaTerm): Either[CompileError, Frag] =
    catalog(mt.name.toLowerCase, mt.value).map { case (frag, params) =>
      mt.polarity match
        case Polarity.Exclude => (s"NOT ($frag)", params)
        case _ => (frag, params)
    }

  private def catalog(name: String, value: MetaValue): Either[CompileError, Frag] = name match
    case "score" => intColumn("score", value)
    case "favcount" => intColumn("fav_count", value)
    case "width" => intColumn("width", value)
    case "height" => intColumn("height", value)
    case "duration" => longColumn("duration", value)
    case "id" => stringColumn("id", value)
    case "rating" => rating(value)
    case "mpixels" => scaledDoubleColumn("(width * height)", 1000000.0, "mpixels", value)
    case "ratio" => scaledDoubleColumn("(width::float8 / NULLIF(height, 0))", 1.0, "ratio", value)
    case "filetype" => exactString("filetype", value)
    case "md5" => exactString("md5", value)
    case "source" => exactString("source", value)
    case "parent" => parent(value)
    case "date" => date(value)
    case "age" => age(value)
    case "is" => videoIs(value)
    case "status" => exactString("status", value) // STUB until moderation/multi-user
    case "filesize" | "fps" | "audio" | "pool" | "ordpool" =>
      Left(CompileError.UnsupportedMetatag(name))
    case other => Left(CompileError.UnsupportedMetatag(other))

  private def op(cmp: Cmp): String = cmp match
    case Cmp.Gt => ">"
    case Cmp.Lt => "<"
    case Cmp.Ge => ">="
    case Cmp.Le => "<="

  private def intColumn(col: String, value: MetaValue): Either[CompileError, Frag] =
    numericColumn(col, value, s => s.toIntOption.map(SqlParam.IntP.apply), "integer")

  private def longColumn(col: String, value: MetaValue): Either[CompileError, Frag] =
    numericColumn(col, value, s => s.toLongOption.map(SqlParam.LongP.apply), "integer")

  private def scaledDoubleColumn(
      expr: String,
      scale: Double,
      name: String,
      value: MetaValue
  ): Either[CompileError, Frag] =
    def parse(s: String): Option[SqlParam] = s.toDoubleOption.map(d => SqlParam.DoubleP(d * scale))
    numericColumn(expr, value, parse, "number", name)

  /**
   * Shared scalar/compare/range shape for a numeric-ish column with a caller-supplied parser.
   * `expr` is the SQL column/expression; `name` is the friendly metatag name used in errors (so a
   * computed `expr` like `(width * height)` never leaks into the user-facing message).
   */
  private def numericColumn(
      expr: String,
      value: MetaValue,
      parse: String => Option[SqlParam],
      kind: String,
      name: String = ""
  ): Either[CompileError, Frag] =
    val label = if name.nonEmpty then name else expr
    def one(raw: String): Either[CompileError, SqlParam] =
      parse(raw).toRight(CompileError.InvalidMetaValue(label, raw, s"expected a $kind"))
    value match
      case MetaValue.Scalar(raw) => one(raw).map(p => (s"$expr = ?", Seq(p)))
      case MetaValue.Compare(cmp, raw) => one(raw).map(p => (s"$expr ${op(cmp)} ?", Seq(p)))
      case MetaValue.RangeVal(lo, hi) => range(expr, lo, hi, one)

  private def range(
      expr: String,
      lo: Option[String],
      hi: Option[String],
      one: String => Either[CompileError, SqlParam]
  ): Either[CompileError, Frag] =
    for
      loP <- traverseOpt(lo)(one)
      hiP <- traverseOpt(hi)(one)
    yield
      val parts = Seq(loP.map(p => (s"$expr >= ?", p)), hiP.map(p => (s"$expr <= ?", p))).flatten
      (parts.map(_._1).mkString("(", " AND ", ")"), parts.map(_._2))

  private def stringColumn(col: String, value: MetaValue): Either[CompileError, Frag] =
    numericColumn(col, value, s => Some(SqlParam.Str(s)), "string")

  private def exactString(col: String, value: MetaValue): Either[CompileError, Frag] = value match
    case MetaValue.Scalar(raw) => Right((s"$col = ?", Seq(SqlParam.Str(raw))))
    case _ =>
      Left(CompileError.InvalidMetaValue(col, value.toString, "only an exact value is supported"))

  private def rating(value: MetaValue): Either[CompileError, Frag] = value match
    case MetaValue.Scalar(raw) if Set("g", "s", "q", "e")(raw) =>
      Right(("rating = ?", Seq(SqlParam.Str(raw))))
    case MetaValue.Scalar(raw) =>
      Left(CompileError.InvalidMetaValue("rating", raw, "must be one of g|s|q|e"))
    case _ =>
      Left(CompileError.InvalidMetaValue("rating", value.toString, "must be an enum g|s|q|e"))

  private def parent(value: MetaValue): Either[CompileError, Frag] = value match
    case MetaValue.Scalar("none") => Right(("parent_id IS NULL", Seq.empty))
    case MetaValue.Scalar(raw) => Right(("parent_id = ?", Seq(SqlParam.Str(raw))))
    case _ =>
      Left(CompileError.InvalidMetaValue("parent", value.toString, "expected 'none' or an id"))

  private def date(value: MetaValue): Either[CompileError, Frag] =
    def one(raw: String): Either[CompileError, SqlParam] =
      Try(LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)).toEither.left
        .map(_ => CompileError.InvalidMetaValue("date", raw, "expected an ISO date (YYYY-MM-DD)"))
        .map(SqlParam.DateP.apply)
    value match
      case MetaValue.Scalar(raw) => one(raw).map(p => ("created_at::date = ?", Seq(p)))
      case MetaValue.Compare(cmp, raw) =>
        one(raw).map(p => (s"created_at::date ${op(cmp)} ?", Seq(p)))
      case MetaValue.RangeVal(lo, hi) => range("created_at::date", lo, hi, one)

  /**
   * Relative recency, best-effort. Value form `<N><unit>` with unit `d`|`h`|`m`
   * (days/hours/minutes) — e.g. `age:<7d` = created in the last 7 days (`created_at >= now() -
   * N*interval`), `age:>30d` = older than 30 days (`<=`). A bare scalar `age:7d` is treated as
   * "within the last N".
   */
  private def age(value: MetaValue): Either[CompileError, Frag] =
    def interval(raw: String): Either[CompileError, (String, SqlParam)] =
      val unit = raw.takeRight(1)
      val numStr = raw.dropRight(1)
      val unitExpr = unit match
        case "d" => Some("interval '1 day'")
        case "h" => Some("interval '1 hour'")
        case "m" => Some("interval '1 minute'")
        case _ => None
      (numStr.toIntOption, unitExpr) match
        case (Some(n), Some(iv)) => Right((iv, SqlParam.IntP(n)))
        case _ =>
          Left(CompileError.InvalidMetaValue("age", raw, "expected <N>d|<N>h|<N>m"))
    def younger(raw: String): Either[CompileError, Frag] =
      interval(raw).map { case (iv, p) => (s"created_at >= now() - (? * $iv)", Seq(p)) }
    def older(raw: String): Either[CompileError, Frag] =
      interval(raw).map { case (iv, p) => (s"created_at <= now() - (? * $iv)", Seq(p)) }
    value match
      case MetaValue.Scalar(raw) => younger(raw)
      case MetaValue.Compare(Cmp.Lt | Cmp.Le, raw) => younger(raw)
      case MetaValue.Compare(Cmp.Gt | Cmp.Ge, raw) => older(raw)
      case MetaValue.RangeVal(_, _) =>
        Left(
          CompileError.InvalidMetaValue("age", value.toString, "ranges are not supported for age")
        )

  private def videoIs(value: MetaValue): Either[CompileError, Frag] = value match
    case MetaValue.Scalar("video") => Right(("duration IS NOT NULL", Seq.empty))
    case MetaValue.Scalar("animated") =>
      // `filetype` is the stored MIME type (`image/gif`), never the bare token `gif`.
      Right(("(duration IS NOT NULL OR filetype = 'image/gif')", Seq.empty))
    case _ =>
      Left(CompileError.InvalidMetaValue("is", value.toString, "expected 'video' or 'animated'"))

  // --- 4.3 ordering, keyset, random --------------------------------------

  private val SupportedOrders =
    Set("id", "score", "favcount", "duration", "mpixels", "date", "random")

  private def validateOrder(order: String): Either[CompileError, Unit] =
    if SupportedOrders(order) then Right(()) else Left(CompileError.UnsupportedOrder(order))

  private def orderColumnExpr(order: String): Option[String] = order match
    case "score" => Some("score")
    case "favcount" => Some("fav_count")
    // COALESCE the NULLABLE order columns so ORDER BY / the keyset row-value `WHERE` / the encoded
    // cursor key all agree on 0 for NULL — otherwise NULLS-FIRST + a NULL row-value comparison
    // silently drops NULL-keyed rows on page 2+ (a keyset GAP). Matches `orderKeyOf`'s getOrElse(0).
    case "duration" => Some("COALESCE(duration, 0)")
    case "mpixels" => Some("COALESCE(width * height, 0)")
    case "date" => Some("created_at")
    case _ => None // id, random

  private def orderByClause(order: String, seed: Option[String]): Either[CompileError, Frag] =
    order match
      case "id" => Right(("id DESC", Seq.empty))
      case "random" =>
        seed
          .toRight(CompileError.InvalidCursor("order:random requires a seed"))
          .map(s => ("md5(id || ?) ASC, id ASC", Seq(SqlParam.Str(s))))
      case _ =>
        orderColumnExpr(order) match
          case Some(expr) => Right((s"$expr DESC, id DESC", Seq.empty))
          case None => Left(CompileError.UnsupportedOrder(order))

  /** The keyset row-value `WHERE` fragment for the next page, or `None` on the first page. */
  private def keysetCondition(
      order: String,
      cursor: Option[Cursor],
      seed: Option[String]
  ): Either[CompileError, Option[Frag]] =
    cursor match
      case None => Right(None)
      case Some(c) => keysetFrag(order, c, seed).map(Some(_))

  private def keysetFrag(
      order: String,
      c: Cursor,
      seed: Option[String]
  ): Either[CompileError, Frag] =
    order match
      case "id" =>
        Right(("id < ?", Seq(SqlParam.Str(c.lastId))))
      case "random" =>
        seed
          .toRight(CompileError.InvalidCursor("order:random cursor requires a seed"))
          .map { s =>
            (
              "(md5(id || ?), id) > (md5(? || ?), ?)",
              Seq(SqlParam.Str(s), SqlParam.Str(c.lastId), SqlParam.Str(s), SqlParam.Str(c.lastId))
            )
          }
      case _ =>
        orderColumnExpr(order) match
          case None => Left(CompileError.UnsupportedOrder(order))
          case Some(expr) =>
            for keyParam <- parseKey(order, c)
            yield (s"($expr, id) < (?, ?)", Seq(keyParam, SqlParam.Str(c.lastId)))

  private def parseKey(order: String, c: Cursor): Either[CompileError, SqlParam] =
    val raw = c.lastKey.toRight(CompileError.InvalidCursor(s"$order cursor is missing its key"))
    order match
      case "score" | "favcount" =>
        raw.flatMap(s => s.toIntOption.map(SqlParam.IntP.apply).toRight(cursorKeyErr(order, s)))
      case "duration" | "mpixels" =>
        raw.flatMap(s => s.toLongOption.map(SqlParam.LongP.apply).toRight(cursorKeyErr(order, s)))
      case "date" =>
        raw.flatMap(s =>
          Try(OffsetDateTime.ofInstant(Instant.parse(s), ZoneOffset.UTC)).toOption
            .map(SqlParam.Timestamp.apply)
            .toRight(cursorKeyErr(order, s))
        )
      case _ => raw.map(SqlParam.Str.apply)

  private def cursorKeyErr(order: String, raw: String): CompileError =
    CompileError.InvalidCursor(s"$order cursor key '$raw' does not parse")

  // --- placeholder numbering ---------------------------------------------

  /**
   * Replace each `?` with a sequential `$1..$n`. Safe because no user value is interpolated (every
   * value is a bind param), so the only `?` in the templated SQL are our placeholders, and their
   * left-to-right order matches the params list.
   */
  private def numberPlaceholders(sql: String): String =
    val parts = sql.split("\\?", -1)
    parts.zipWithIndex.map { case (part, i) => if i == 0 then part else s"$$$i$part" }.mkString

  // --- small traversals ---------------------------------------------------

  private def traverse[A, B](
      xs: Seq[A]
  )(f: A => Either[CompileError, B]): Either[CompileError, Seq[B]] =
    xs.foldLeft(Right(Vector.empty): Either[CompileError, Vector[B]]) { (acc, a) =>
      for soFar <- acc; b <- f(a) yield soFar :+ b
    }

  private def traverseOpt[A, B](o: Option[A])(
      f: A => Either[CompileError, B]
  ): Either[CompileError, Option[B]] =
    o match
      case None => Right(None)
      case Some(a) => f(a).map(Some(_))
