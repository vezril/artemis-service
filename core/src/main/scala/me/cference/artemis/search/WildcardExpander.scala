package me.cference.artemis.search

import me.cference.artemis.domain.Tag

import scala.concurrent.Future

/**
 * Port for query-time wildcard tag expansion (search-dsl spec, "Wildcard tag expansion is
 * bounded"). Given a tag pattern containing `*`, resolve it to the concrete matching tags via the
 * trigram-indexed `tags` table. The expansion is capped (top-N by `post_count`): a pattern matching
 * more than the cap yields `Left(WildcardTooBroad)` instead of running unbounded.
 *
 * The concrete implementation lives in the server module (`ReadModelWildcardExpander`); the core
 * planner depends only on this pure port so it stays DB-free and testable with a fake.
 */
trait WildcardExpander:
  def expand(pattern: String): Future[Either[WildcardTooBroad, Set[Tag]]]
