package me.cference.artemis.projection

import me.cference.artemis.domain.PostEvent
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcProjection
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.apache.pekko.projection.{Projection, ProjectionId}

import scala.collection.immutable

/**
 * Builds the post read-model projection: `eventsBySlices[PostEvent]` over entity type `post`
 * (persistence id `post|<id>`), folding each event into the `posts`/`tags` read tables via
 * [[PostProjectionHandler]]. The runtime distributes N slice ranges across the cluster; tests use
 * the single full-range convenience `apply(repo)`.
 */
object PostProjection:

  val EntityType = "post"

  def sliceRanges(numberOfInstances: Int)(using system: ActorSystem[?]): immutable.Seq[Range] =
    EventSourcedProvider.sliceRanges(system, R2dbcReadJournal.Identifier, numberOfInstances)

  def forRange(repo: ReadModelRepository, sliceRange: Range)(using
      system: ActorSystem[?]
  ): Projection[EventEnvelope[PostEvent]] =
    val sourceProvider
        : SourceProvider[org.apache.pekko.persistence.query.Offset, EventEnvelope[PostEvent]] =
      EventSourcedProvider.eventsBySlices[PostEvent](
        system,
        R2dbcReadJournal.Identifier,
        EntityType,
        sliceRange.min,
        sliceRange.max
      )
    R2dbcProjection.atLeastOnce(
      projectionId = ProjectionId("post-read-model", s"${sliceRange.min}-${sliceRange.max}"),
      settings = None,
      sourceProvider = sourceProvider,
      handler = () => new PostProjectionHandler(repo)(using system.executionContext)
    )(system)

  /** Single full-range projection (all 1024 slices) — used by tests. */
  def apply(repo: ReadModelRepository)(using
      system: ActorSystem[?]
  ): Projection[EventEnvelope[PostEvent]] =
    forRange(repo, 0 to 1023)
