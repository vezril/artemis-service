package me.cference.artemis.search

import me.cference.artemis.domain.Tag
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Unit tests (no DB) for [[SqlCompiler]] — the search-dsl SQL compilation (tasks 4.1-4.3). Asserts
 * the parameterized SQL fragments and the bound params: every user value is a bind param (`$n` +
 * [[SqlParam]]), never string-interpolated. Round-trip execution is proven in `SearchQueryIT`.
 */
final class SqlCompilerSpec extends AnyWordSpec with Matchers with EitherValues:

  import CompileError.*
  import MetaValue.*

  // --- fixtures -----------------------------------------------------------

  private def plan(
      includes: Set[String] = Set.empty,
      excludes: Set[String] = Set.empty,
      orGroups: Seq[Set[String]] = Nil,
      predicates: Seq[MetaTerm] = Nil,
      order: Option[String] = None,
      limit: Option[Int] = None
  ): QueryPlan =
    QueryPlan(
      includes.map(Tag.unsafe),
      excludes.map(Tag.unsafe),
      orGroups.map(_.map(Tag.unsafe)),
      predicates,
      order,
      limit
    )

  private def compile(
      p: QueryPlan,
      cursor: Option[Cursor] = None,
      pageSize: Int = 50,
      seed: Option[String] = None
  ): Either[CompileError, CompiledQuery] =
    SqlCompiler.compile(p, cursor, pageSize, seed)

  private def sqlOf(p: QueryPlan): String = compile(p).value.sql

  private def meta(
      name: String,
      value: MetaValue,
      polarity: Polarity = Polarity.Include
  ): MetaTerm =
    MetaTerm(polarity, name, value)

  // --- 4.1 tag WHERE core -------------------------------------------------

  "SqlCompiler (browse-all)" should {

    "compile the EMPTY plan to the visible catalog, newest first" in {
      // The browse-all short-circuit (search-dsl "An empty query is browse-all") hands the
      // compiler a fully empty plan: only the status default remains in the WHERE, and the
      // default ordering is id DESC (ULIDs → most recent first).
      val sql = sqlOf(plan())
      sql should include("status <> 'deleted'")
      sql should include("ORDER BY created_at DESC, id DESC")
      sql should include("LIMIT")
      (sql should not).include("tags @>")
      (sql should not).include("tags &&")
    }
  }

  "SqlCompiler (4.1 tag WHERE)" should {

    "compile includes to a GIN set-containment `tags @>` with a text[] param" in {
      val cq = compile(plan(includes = Set("1girl", "cat_ears"))).value
      cq.sql should include("tags @>")
      cq.params should contain(SqlParam.TextArray(Seq("1girl", "cat_ears")))
    }

    "compile excludes to `NOT (tags && ...)`" in {
      val cq = compile(plan(includes = Set("1girl"), excludes = Set("monochrome"))).value
      cq.sql should include("NOT (tags &&")
      cq.params should contain(SqlParam.TextArray(Seq("monochrome")))
    }

    "compile a single OR group to `(tags && ...)`" in {
      val cq =
        compile(plan(includes = Set("animal"), orGroups = Seq(Set("cat_ears", "dog_ears")))).value
      cq.sql should include("(tags &&")
      cq.params should contain(SqlParam.TextArray(Seq("cat_ears", "dog_ears")))
    }

    "compile multiple OR groups as separate ANDed `(tags && ...)` terms" in {
      val cq = compile(plan(includes = Set("a"), orGroups = Seq(Set("x"), Set("y")))).value
      raw"\(tags &&".r.findAllIn(cq.sql).size shouldBe 2
    }

    "render an EMPTY OR group as the literal FALSE (matches nothing)" in {
      sqlOf(plan(includes = Set("a"), orGroups = Seq(Set.empty))) should include("FALSE")
    }

    "be insensitive to include order and repetition" in {
      sqlOf(plan(includes = Set("a", "b"))) shouldBe sqlOf(plan(includes = Set("b", "a")))
    }

    "add the default not-deleted status filter when no status predicate is present" in {
      sqlOf(plan(includes = Set("a"))) should include("status <> 'deleted'")
    }

    "omit the default not-deleted filter when a status predicate is present" in {
      val cq = compile(
        plan(includes = Set("a"), predicates = Seq(meta("status", Scalar("pending"))))
      ).value
      cq.sql should not include "status <> 'deleted'"
      cq.sql should include("status = ?".replace("?", "$"))
    }
  }

  // --- 4.2 metatag catalog ------------------------------------------------

  "SqlCompiler (4.2 metatags)" should {

    "compile score:>10 to a `score > ?` comparison with an int param" in {
      val cq = compile(
        plan(includes = Set("a"), predicates = Seq(meta("score", Compare(Cmp.Gt, "10"))))
      ).value
      cq.sql should include("score >")
      cq.params should contain(SqlParam.IntP(10))
    }

    "compile is:video to a duration-present filter" in {
      val cq =
        compile(plan(includes = Set("a"), predicates = Seq(meta("is", Scalar("video"))))).value
      cq.sql should include("duration IS NOT NULL")
    }

    "compile is:animated to match videos or the image/gif MIME type" in {
      val cq =
        compile(plan(includes = Set("a"), predicates = Seq(meta("is", Scalar("animated"))))).value
      cq.sql should include("filetype = 'image/gif'")
    }

    "compile id:100..200 as a string range (string-id caveat)" in {
      val cq = compile(
        plan(includes = Set("a"), predicates = Seq(meta("id", RangeVal(Some("100"), Some("200")))))
      ).value
      cq.sql should include("id >=")
      cq.sql should include("id <=")
      cq.params should contain(SqlParam.Str("100"))
      cq.params should contain(SqlParam.Str("200"))
    }

    "compile rating:g and rating:e as an enum equality" in {
      compile(
        plan(includes = Set("a"), predicates = Seq(meta("rating", Scalar("g"))))
      ).value.params should
        contain(SqlParam.Str("g"))
      compile(
        plan(includes = Set("a"), predicates = Seq(meta("rating", Scalar("e"))))
      ).value.params should
        contain(SqlParam.Str("e"))
    }

    "reject an out-of-range rating value" in {
      compile(
        plan(includes = Set("a"), predicates = Seq(meta("rating", Scalar("z"))))
      ).left.value shouldBe
        a[InvalidMetaValue]
    }

    "compile duration:30..120 with is:video" in {
      val cq = compile(
        plan(
          includes = Set("a"),
          predicates =
            Seq(meta("duration", RangeVal(Some("30"), Some("120"))), meta("is", Scalar("video")))
        )
      ).value
      cq.sql should include("duration >=")
      cq.sql should include("duration <=")
      cq.sql should include("duration IS NOT NULL")
      cq.params should contain(SqlParam.LongP(30L))
      cq.params should contain(SqlParam.LongP(120L))
    }

    "defer filesize with a typed UnsupportedMetatag error naming the tag" in {
      compile(
        plan(includes = Set("a"), predicates = Seq(meta("filesize", Compare(Cmp.Gt, "1000"))))
      ).left.value shouldBe
        UnsupportedMetatag("filesize")
    }

    "defer fps, audio, pool, and ordpool as unsupported" in {
      Seq("fps", "audio", "pool", "ordpool").foreach { name =>
        compile(
          plan(includes = Set("a"), predicates = Seq(meta(name, Scalar("x"))))
        ).left.value shouldBe
          UnsupportedMetatag(name)
      }
    }

    "reject a non-numeric score with a typed error" in {
      compile(
        plan(includes = Set("a"), predicates = Seq(meta("score", Scalar("abc"))))
      ).left.value shouldBe
        a[InvalidMetaValue]
    }

    "wrap an excluded metatag in NOT (...)" in {
      val cq = compile(
        plan(includes = Set("a"), predicates = Seq(meta("rating", Scalar("e"), Polarity.Exclude)))
      ).value
      cq.sql should include("NOT (rating =")
      cq.params should contain(SqlParam.Str("e"))
    }

    "compile parent:none to an IS NULL with no param" in {
      sqlOf(plan(includes = Set("a"), predicates = Seq(meta("parent", Scalar("none"))))) should
        include("parent_id IS NULL")
    }
  }

  // --- 4.3 ordering / keyset / random / clamp -----------------------------

  "SqlCompiler (4.3 ordering & pagination)" should {

    "order by the selected column descending with an id tiebreak" in {
      sqlOf(plan(includes = Set("a"), order = Some("score"))) should include(
        "ORDER BY score DESC, id DESC"
      )
    }

    "default to created_at DESC, id DESC when no order is given" in {
      sqlOf(plan(includes = Set("a"))) should include("ORDER BY created_at DESC, id DESC")
    }

    "reject an unsupported/deferred order (filesize)" in {
      compile(plan(includes = Set("a"), order = Some("filesize"))).left.value shouldBe
        UnsupportedOrder("filesize")
    }

    "emit a keyset row-value WHERE from a cursor" in {
      val cur = Cursor("score", "p9", Some("42"), None)
      val cq = compile(plan(includes = Set("a"), order = Some("score")), cursor = Some(cur)).value
      cq.sql should include("(score, id) <")
      cq.params should contain(SqlParam.IntP(42))
      cq.params should contain(SqlParam.Str("p9"))
    }

    "clamp an over-large limit to MaxPageSize" in {
      val cq = compile(plan(includes = Set("a"), limit = Some(100000)), pageSize = 100000).value
      cq.params.lastOption shouldBe Some(SqlParam.IntP(SqlCompiler.MaxPageSize))
      SqlCompiler.effectiveLimit(
        plan(includes = Set("a"), limit = Some(100000)),
        50
      ) shouldBe SqlCompiler.MaxPageSize
    }

    "emit a stable seeded md5 shuffle for order:random" in {
      val cq =
        compile(plan(includes = Set("a"), order = Some("random")), seed = Some("seed123")).value
      cq.sql should include("md5(id ||")
      cq.params should contain(SqlParam.Str("seed123"))
    }
  }

  // --- compileWhere (shared plan→WHERE for facets) ------------------------

  "SqlCompiler.compileWhere" should {

    "produce just the WHERE predicate — tag containment + status default, no ORDER BY/LIMIT" in {
      val cq = SqlCompiler.compileWhere(plan(includes = Set("1girl"))).value
      cq.sql should include("tags @>")
      cq.sql should include("status <> 'deleted'")
      cq.sql should not include "ORDER BY"
      cq.sql should not include "LIMIT"
      cq.sql should not include "SELECT"
      cq.params should contain(SqlParam.TextArray(Seq("1girl")))
    }

    "carry metatag predicates and number placeholders from $1" in {
      val cq = SqlCompiler
        .compileWhere(
          plan(includes = Set("a"), predicates = Seq(meta("score", Compare(Cmp.Gt, "10"))))
        )
        .value
      cq.sql should include("score >")
      cq.sql should include("$1")
      cq.sql should not include "?"
      cq.params should contain(SqlParam.IntP(10))
    }

    "surface an unsupported metatag as a Left rather than a WHERE" in {
      SqlCompiler
        .compileWhere(
          plan(includes = Set("a"), predicates = Seq(meta("filesize", Compare(Cmp.Gt, "1"))))
        )
        .left
        .value shouldBe UnsupportedMetatag("filesize")
    }
  }

  // --- injection-safety invariant -----------------------------------------

  "SqlCompiler placeholder numbering" should {

    "number every bind param sequentially and leave no literal ? behind" in {
      val cur = Cursor("score", "p9", Some("42"), None)
      val cq = compile(
        plan(
          includes = Set("1girl"),
          excludes = Set("monochrome"),
          orGroups = Seq(Set("cat_ears", "dog_ears")),
          predicates = Seq(meta("score", Compare(Cmp.Gt, "10")), meta("rating", Scalar("s"))),
          order = Some("score")
        ),
        cursor = Some(cur)
      ).value
      cq.sql should not include "?"
      raw"\$$\d+".r.findAllIn(cq.sql).size shouldBe cq.params.size
    }
  }

  // --- cursor encoding ----------------------------------------------------

  "Cursor" should {
    "round-trip through encode/decode" in {
      val cur = Cursor("random", "p42", Some("k"), Some("seedX"))
      Cursor.decode(cur.encode).value shouldBe cur
    }

    "round-trip a cursor with no key and no seed" in {
      val cur = Cursor("id", "p1", None, None)
      Cursor.decode(cur.encode).value shouldBe cur
    }

    "reject a malformed cursor" in {
      Cursor.decode("not-a-valid-cursor").left.value shouldBe a[CompileError]
    }
  }
