package me.cference.artemis.search

import me.cference.artemis.domain.Tag
import me.cference.artemis.projection.ReadModelRepository

import scala.concurrent.{ExecutionContext, Future}

/**
 * Postgres-backed [[WildcardExpander]] (search-dsl "Wildcard tag expansion is bounded", task 3.2).
 * Translates a `*` tag pattern to a SQL `LIKE` against the trigram-indexed `tags.name`, orders by
 * `post_count DESC`, and fetches `cap + 1` rows: more than `cap` matches ⇒ `WildcardTooBroad`
 * (never an unbounded scan), otherwise the concrete matches as a `Set[Tag]`.
 *
 * `*` maps to `%`; literal `_`, `%`, and `\` in the pattern are escaped so they match literally
 * (the SQL uses `ESCAPE '\'`). Matched names come from the projection-maintained tag table and are
 * already canonical, so they are lifted with [[Tag.unsafe]].
 */
final class ReadModelWildcardExpander(repo: ReadModelRepository, cap: Int)(using
    ec: ExecutionContext
) extends WildcardExpander:

  def expand(pattern: String): Future[Either[WildcardTooBroad, Set[Tag]]] =
    repo.matchTagNames(toLikePattern(pattern), cap + 1).map { names =>
      if names.sizeIs > cap then Left(WildcardTooBroad(pattern))
      else Right(names.map(Tag.unsafe).toSet)
    }

  /**
   * `*` → `%`; escape the LIKE metacharacters and the escape char itself so they match literally.
   */
  private def toLikePattern(pattern: String): String =
    pattern.iterator.flatMap {
      case '*' => "%"
      case c @ ('_' | '%' | '\\') => s"\\$c"
      case c => c.toString
    }.mkString
