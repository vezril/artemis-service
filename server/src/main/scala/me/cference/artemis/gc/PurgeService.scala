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

  /** Delete the purged post's exact blob keys; best-effort per blob. */
  private def deleteBlobs(keys: Seq[String]): Future[Unit] =
    sequentially(keys) { key =>
      blobs.delete(key).recover { case NonFatal(e) =>
        log.warn(
          "purge: failed to delete blob {} ({}); orphan sweep reclaims leftover originals",
          key,
          e.getMessage
        )
        ()
      }
    }

  private def sequentially[A](items: Seq[A])(f: A => Future[Unit]): Future[Unit] =
    items.foldLeft(Future.successful(()))((acc, item) => acc.flatMap(_ => f(item)))

  private def foldCount[A](items: Seq[A])(f: A => Future[Boolean]): Future[Int] =
    items.foldLeft(Future.successful(0)) { (acc, item) =>
      acc.flatMap(n => f(item).map(done => if done then n + 1 else n))
    }
