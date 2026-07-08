package me.cference.artemis.projection

import me.cference.artemis.domain.PoolEvent
import me.cference.artemis.domain.PoolEvent.*
import org.apache.pekko.Done
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.projection.r2dbc.scaladsl.{R2dbcHandler, R2dbcSession}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Folds pool journal events into the read model. Idempotent: `upsertPool` is keyed by id,
 * `addPoolPost` uses `ON CONFLICT DO NOTHING` (with position from current membership size), and
 * delete/remove are naturally idempotent, so at-least-once redelivery and rebuild-from-empty are
 * safe. The pool id comes from the persistence id (`pool|<id>`).
 */
final class PoolProjectionHandler(repo: ReadModelRepository)(using ec: ExecutionContext)
    extends R2dbcHandler[EventEnvelope[PoolEvent]]:

  def process(session: R2dbcSession, envelope: EventEnvelope[PoolEvent]): Future[Done] =
    val id = envelope.persistenceId.substring(envelope.persistenceId.indexOf('|') + 1)
    val applied = envelope.event match
      case e: PoolCreated => repo.upsertPool(id, e.name.value)
      case e: PoolRenamed => repo.renamePool(id, e.name.value)
      case _: PoolDeleted => repo.deletePool(id)
      case e: PostAdded => repo.addPoolPost(id, e.post.value)
      case e: PostRemoved => repo.removePoolPost(id, e.post.value)
      case e: Reordered => repo.reorderPool(id, e.order.map(_.value))
    applied.map(_ => Done)
