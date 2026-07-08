package me.cference.artemis.projection

import me.cference.artemis.domain.PoolEvent
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.r2dbc.scaladsl.R2dbcProjection
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.apache.pekko.projection.{Projection, ProjectionId}

import scala.collection.immutable

/**
 * Builds the pool read-model projection: `eventsBySlices[PoolEvent]` over entity type `pool`
 * (persistence id `pool|<id>`), folding each event into the `pools`/`pool_posts` read tables via
 * [[PoolProjectionHandler]]. The runtime distributes N slice ranges across the cluster; tests use
 * the single full-range convenience `apply(repo)`.
 */
object PoolProjection:

  val EntityType = "pool"

  def sliceRanges(numberOfInstances: Int)(using system: ActorSystem[?]): immutable.Seq[Range] =
    EventSourcedProvider.sliceRanges(system, R2dbcReadJournal.Identifier, numberOfInstances)

  def forRange(repo: ReadModelRepository, sliceRange: Range)(using
      system: ActorSystem[?]
  ): Projection[EventEnvelope[PoolEvent]] =
    val sourceProvider
        : SourceProvider[org.apache.pekko.persistence.query.Offset, EventEnvelope[PoolEvent]] =
      EventSourcedProvider.eventsBySlices[PoolEvent](
        system,
        R2dbcReadJournal.Identifier,
        EntityType,
        sliceRange.min,
        sliceRange.max
      )
    R2dbcProjection.atLeastOnce(
      projectionId = ProjectionId("pool-read-model", s"${sliceRange.min}-${sliceRange.max}"),
      settings = None,
      sourceProvider = sourceProvider,
      handler = () => new PoolProjectionHandler(repo)(using system.executionContext)
    )(system)

  /** Single full-range projection (all 1024 slices) — used by tests. */
  def apply(repo: ReadModelRepository)(using
      system: ActorSystem[?]
  ): Projection[EventEnvelope[PoolEvent]] =
    forRange(repo, 0 to 1023)
