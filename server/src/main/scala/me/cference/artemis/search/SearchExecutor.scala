package me.cference.artemis.search

import me.cference.artemis.projection.{PostRow, ReadModelRepository}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/** One page of search results plus the opaque cursor for the next page (`None` at the end). */
final case class SearchPage(rows: Seq[PostRow], nextCursor: Option[String])

/**
 * Executes a compiled [[QueryPlan]] against the `posts` read model with keyset pagination
 * (search-dsl task 4.4). Decodes the incoming cursor, resolves the `order:random` seed (generated
 * once server-side on the first page, then carried in the cursor so paging is stable), compiles the
 * plan via [[SqlCompiler]], runs it through [[ReadModelRepository.selectPostRows]], and computes
 * the next cursor from the last row of a full page.
 *
 * A compile failure (bad/unsupported metatag, malformed cursor) surfaces as `Left(CompileError)` —
 * errors-as-values, never a thrown exception or a degraded query.
 */
final class SearchExecutor(repo: ReadModelRepository)(using ec: ExecutionContext):

  def search(
      plan: QueryPlan,
      cursor: Option[String],
      pageSize: Int
  ): Future[Either[CompileError, SearchPage]] =
    decodeCursor(cursor) match
      case Left(err) => Future.successful(Left(err))
      case Right(decoded) =>
        val order = SqlCompiler.normalizeOrder(plan)
        val seed = decoded.flatMap(_.seed).orElse(Option.when(order == "random")(freshSeed()))
        SqlCompiler.compile(plan, decoded, pageSize, seed) match
          case Left(err) => Future.successful(Left(err))
          case Right(compiled) =>
            repo
              .selectPostRows(compiled.sql, stmt => SqlParam.bindAll(stmt, compiled.params))
              .map(rows => Right(SearchPage(rows, nextCursor(plan, pageSize, order, seed, rows))))

  private def decodeCursor(cursor: Option[String]): Either[CompileError, Option[Cursor]] =
    cursor match
      case None => Right(None)
      case Some(raw) => Cursor.decode(raw).map(Some(_))

  /** A next cursor only when the page came back full — a short page is the last page. */
  private def nextCursor(
      plan: QueryPlan,
      pageSize: Int,
      order: String,
      seed: Option[String],
      rows: Seq[PostRow]
  ): Option[String] =
    val limit = SqlCompiler.effectiveLimit(plan, pageSize)
    Option.when(rows.nonEmpty && rows.sizeIs == limit) {
      val last = rows.last
      Cursor(order, last.id, SqlCompiler.orderKeyOf(order, last), seed).encode
    }

  private def freshSeed(): String = UUID.randomUUID().toString.replace("-", "")
