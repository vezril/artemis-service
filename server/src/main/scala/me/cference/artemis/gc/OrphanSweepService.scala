package me.cference.artemis.gc

import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * The failed-upload orphan sweep (deletion-lifecycle spec): list the `originals/` blobs, subtract
 * every md5 still referenced by a post (via [[OrphanSweep.plan]]), and delete the leftovers —
 * debris from uploads whose post was never committed. Supports a **dry-run** that reports the plan
 * without deleting.
 *
 * Deliberately NOT scheduled: per the design this is a manual/occasional janitorial tool (run
 * dry-run first), driven on demand from the admin API. In-flight protection comes from the
 * referenced set including `pending` posts (see [[OrphanSweep]]).
 *
 * The read it needs is injected as a plain `referencedMd5s` function (mirroring [[PurgeService]]'s
 * `softDeletedBefore`/`purgeTargetFor` seams) rather than the whole `ReadModelRepository`, so the
 * service tests with no DB.
 */
final class OrphanSweepService(referencedMd5s: () => Future[Set[String]], blobs: BlobStore)(using
    ec: ExecutionContext
):

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Compute the orphan plan and, unless `dryRun`, delete each orphan best-effort. Returns the
   * counts: how many originals were `scanned`, how many were `orphans`, and how many were actually
   * `deleted` (0 on a dry-run; on a real run, the number of successful deletes — a failed delete is
   * logged and reclaimed on a future pass, never failing the whole sweep).
   */
  def sweep(dryRun: Boolean): Future[OrphanSweepService.SweepResult] =
    for
      listed <- blobs.list("originals/")
      referenced <- referencedMd5s()
      orphans = OrphanSweep.plan(listed, referenced)
      deleted <-
        if dryRun then
          log.info("orphan sweep (dry-run): {} orphan blob(s) would be deleted", orphans.size)
          Future.successful(0)
        else
          log.info("orphan sweep: deleting {} orphan blob(s)", orphans.size)
          deleteOrphans(orphans)
    yield OrphanSweepService.SweepResult(scanned = listed.size, orphans = orphans.size, deleted)

  /** Delete each orphan best-effort, returning the count that actually succeeded. */
  private def deleteOrphans(orphans: Seq[BlobRef]): Future[Int] =
    orphans.foldLeft(Future.successful(0)) { (acc, orphan) =>
      acc.flatMap { n =>
        blobs
          .delete(orphan.key)
          .map(_ => n + 1)
          .recover { case NonFatal(e) =>
            log.warn(
              "orphan sweep: failed to delete {} ({}); left for a future pass",
              orphan.key,
              e.getMessage
            )
            n
          }
      }
    }

object OrphanSweepService:

  /** The outcome of one sweep: originals scanned, orphans planned, and orphans actually deleted. */
  final case class SweepResult(scanned: Int, orphans: Int, deleted: Int)
