package me.cference.artemis.gc

import me.cference.artemis.projection.ReadModelRepository
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

/**
 * The failed-upload orphan sweep (deletion-lifecycle spec): list the `originals/` blobs, subtract
 * every md5 still referenced by a post (via [[OrphanSweep.plan]]), and delete the leftovers —
 * debris from uploads whose post was never committed. Supports a **dry-run** that reports the plan
 * without deleting.
 *
 * Deliberately NOT scheduled: per the design this is a manual/occasional janitorial tool (run
 * dry-run first), so it's exposed as a method rather than a loop — no `Main` wiring drives it
 * automatically. In-flight protection comes from the referenced set including `pending` posts (see
 * [[OrphanSweep]]).
 */
final class OrphanSweepService(repo: ReadModelRepository, blobs: BlobStore)(using
    ec: ExecutionContext
):

  private val log = LoggerFactory.getLogger(getClass)

  /** Compute the orphan plan and, unless `dryRun`, delete each orphan. Returns the plan. */
  def sweep(dryRun: Boolean): Future[Seq[BlobRef]] =
    for
      listed <- blobs.list("originals/")
      referenced <- repo.referencedMd5s()
      orphans = OrphanSweep.plan(listed, referenced)
      _ <-
        if dryRun then
          log.info("orphan sweep (dry-run): {} orphan blob(s) would be deleted", orphans.size)
          Future.successful(())
        else
          log.info("orphan sweep: deleting {} orphan blob(s)", orphans.size)
          Future
            .sequence(orphans.map(o => blobs.delete(o.key)))
            .map(_ => ())
    yield orphans
