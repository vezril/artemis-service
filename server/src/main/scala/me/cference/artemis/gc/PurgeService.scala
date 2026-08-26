package me.cference.artemis.gc

import me.cference.artemis.domain.PostCommand.Purge
import me.cference.artemis.persistence.PostEntity
import me.cference.artemis.projection.PurgeTarget
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * The retention-based auto-purge job (deletion-lifecycle spec): one pass finds soft-deleted posts
 * older than the retention window and, for each, permanently purges the post FIRST (`Purge` →
 * `PostPurged`, which removes its read-model row), then deletes its blobs 1:1 (the exact
 * `originals/…` + `derivatives/…` keys the read model recorded). Because storage is 1:1
 * (deduplicated-ingest), deleting a purged post's blobs can never affect another post.
 *
 * Ordering matters: `Purge` runs before any blob delete so the single-writer aggregate atomically
 * confirms the post is still `Deleted`. If it was concurrently restored (a re-upload between the
 * work-list read and this pass), `Purge` is rejected (`PostNotFound`) and NO blobs are deleted —
 * the now-active post keeps its original. Only after a confirmed purge are the blobs removed,
 * best-effort per blob (a stray already-gone/unavailable blob is logged, not fatal — the post is
 * already purged, and a leftover original is reclaimable by the orphan sweep). Within-retention
 * posts are left intact (still restorable). Driven on a schedule from `Main`.
 */
final class PurgeService(
    softDeletedBefore: Instant => Future[Seq[PurgeTarget]],
    purgeTargetFor: String => Future[Option[PurgeTarget]],
    blobs: BlobStore,
    postFor: String => RecipientRef[PostEntity.Command],
    retention: FiniteDuration
)(using system: ActorSystem[?], timeout: Timeout):

  private given scala.concurrent.ExecutionContext = system.executionContext
  private val log = LoggerFactory.getLogger(getClass)

  /** Run one retention pass as of `now`; returns the number of posts actually purged. */
  def purgeDue(now: Instant): Future[Int] =
    val cutoff = now.minusMillis(retention.toMillis)
    softDeletedBefore(cutoff).flatMap { due =>
      foldCount(due) { target =>
        purge(target.id).flatMap {
          case true => deleteBlobs(target.blobKeys).map(_ => true)
          case false => Future.successful(false) // restored/gone since the read — leave its blobs
        }
      }
    }

  /**
   * Immediately purge the single soft-deleted post `id`, bypassing the retention window: purge the
   * aggregate FIRST (the `Purge` command atomically confirms it is still `Deleted`), then delete
   * its exact blobs best-effort. A post that is not currently `Deleted` (never existed,
   * active/restored, or already purged) resolves to no target and is an accepted no-op
   * (`PurgeOutcome(false, 0)`); a post whose row is present but whose aggregate is no longer
   * `Deleted` is likewise not purged.
   *
   * Known limitation (harmless, single-user scale): the read-model row is removed asynchronously
   * when the projection consumes `PostPurged`. In the brief window between the aggregate committing
   * that event and the row being deleted, a second `purgeNow(id)` (e.g. a client retry) still
   * resolves the same target and — because `Purge` on an already-`Purged` aggregate is an
   * idempotent accept — reports `PurgeOutcome(true, …)` again and re-issues the (idempotent) blob
   * deletes. It is never data-unsafe (1:1 content-addressed storage; deletes are idempotent), only
   * a possibly-double "purged:true". A precise once-only signal would need the entity to report
   * event-appended vs. no-op, which is out of scope here.
   */
  def purgeNow(id: String): Future[PurgeService.PurgeOutcome] =
    purgeTargetFor(id).flatMap {
      case None => Future.successful(PurgeService.PurgeOutcome(false, 0))
      case Some(target) =>
        purge(target.id).flatMap {
          case true => deleteBlobs(target.blobKeys).map(PurgeService.PurgeOutcome(true, _))
          case false => Future.successful(PurgeService.PurgeOutcome(false, 0))
        }
    }

  /**
   * Purge the post; `true` iff it was (or already is) purged, `false` if it is no longer Deleted.
   */
  private def purge(id: String): Future[Boolean] =
    postFor(id)
      .askWithStatus[org.apache.pekko.Done](PostEntity.Execute(Purge(Instant.now()), _))
      .map(_ => true)
      .recover { case NonFatal(e) =>
        // Rejected because the post is no longer Deleted (e.g. restored by a race), or a transient
        // ask failure — either way skip blob deletion this pass and retry next.
        log.info("purge: post {} not purged this pass ({}); leaving blobs", id, e.getMessage)
        false
      }

  /**
   * Delete the purged post's exact blob keys; best-effort per blob, returning the success count.
   */
  private def deleteBlobs(keys: Seq[String]): Future[Int] =
    keys.foldLeft(Future.successful(0)) { (acc, key) =>
      acc.flatMap { n =>
        blobs
          .delete(key)
          .map(_ => n + 1)
          .recover { case NonFatal(e) =>
            log.warn(
              "purge: failed to delete blob {} ({}); orphan sweep reclaims leftover originals",
              key,
              e.getMessage
            )
            n
          }
      }
    }

  private def foldCount[A](items: Seq[A])(f: A => Future[Boolean]): Future[Int] =
    items.foldLeft(Future.successful(0)) { (acc, item) =>
      acc.flatMap(n => f(item).map(done => if done then n + 1 else n))
    }

object PurgeService:
  /**
   * The outcome of a single-post [[PurgeService.purgeNow]]: whether it purged, and blobs removed.
   */
  final case class PurgeOutcome(purged: Boolean, blobsDeleted: Int)
