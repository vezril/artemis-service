package me.cference.artemis.ingest

import codex.messages.v1.{MediaFailed, MediaProcessed, TagJob}
import me.cference.artemis.domain.PostCommand.{FlagPossibleDuplicate, MarkFailed, RecordProcessed}
import me.cference.artemis.domain.{Derivative, Dimensions, Phash, PostCommand, PostId}
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.Future
import scala.util.control.NonFatal

/**
 * The consume path (OpenSpec task 4.2): drives a post from Hephaestus results. `MediaProcessed`
 * issues `RecordProcessed` (dimensions/derivatives/phash) taking the post to `active`;
 * `MediaFailed` issues `MarkFailed` taking it to `failed`.
 *
 * Redelivery-safe: the domain transitions are themselves idempotent (`RecordProcessed` on an active
 * post refreshes to the same facts; `MarkFailed` on an already-`Failed` post is an accepted no-op),
 * so an at-least-once (or concurrent) redelivery of a `jobId` never double-applies or surfaces a
 * spurious rejection. The [[ProcessedJobs]] guard is a cheap optimization on top of that — it
 * avoids re-issuing a command for a jobId already seen — not the correctness mechanism.
 */
final class MediaResultHandler(
    processedJobs: ProcessedJobs,
    postFor: String => RecipientRef[PostEntity.Command],
    nearDuplicates: NearDuplicates = NearDuplicates.none,
    tagJobs: TagJobPublisher = TagJobPublisher.none
)(using system: ActorSystem[?], timeout: Timeout):

  // MDC-propagating so the correlation id adopted by the consumer rides this handler's async work
  // (the activation/dup-flag/tag-publish Futures) onto their log lines (request-tracing).
  private given scala.concurrent.ExecutionContext =
    me.cference.artemis.tracing.MdcPropagatingExecutionContext(system.executionContext)
  private val log = LoggerFactory.getLogger(getClass)

  def onProcessed(m: MediaProcessed): Future[Unit] =
    // A successful process must carry valid metadata; absent/invalid dimensions fail the Future
    // (retry / dead-letter) rather than activating a post with fabricated 0x0 dimensions.
    dimensionsOf(m) match
      case Left(err) => Future.failed(new IllegalArgumentException(err))
      case Right(dims) =>
        once(m.jobId) {
          // Post-processing (option B): after the post is active, compare its phash against
          // existing posts and flag it if a near-duplicate is found. Inside the once(jobId) guard,
          // so redelivery of the same job never re-flags. A `None` match leaves the post unique.
          // Then publish the TagJob (auto-tagging) — best-effort, decoupled, also inside the guard
          // so a redelivered job never re-publishes.
          execute(
            m.postId,
            RecordProcessed(dims, derivatives(m), Phash(m.phash), now(), m.specVersion)
          )
            .flatMap(_ => bestEffortFlag(m))
            .flatMap(_ => bestEffortPublishTagJob(m))
        }

  /**
   * Detect a near-duplicate for the just-activated post and issue the dup-flag command if found.
   * Best-effort: the dup notice is a post-processing WARNING that must never block ingest, so a
   * failure (e.g. the read model is momentarily unavailable) degrades to "not flagged" rather than
   * failing `onProcessed` — which would skip `markApplied` and wedge the job in redelivery. The
   * activation itself is already durable and idempotent on replay.
   */
  private def bestEffortFlag(m: MediaProcessed): Future[Done] =
    flagIfDuplicate(m).recover { case NonFatal(e) =>
      log.warn(
        "duplicate detection failed for post {} (job {}): {}",
        m.postId,
        m.jobId,
        e.getMessage
      )
      Done
    }

  private def flagIfDuplicate(m: MediaProcessed): Future[Done] =
    nearDuplicates.findNear(m.phash, m.postId).flatMap {
      case Some(matchedId) =>
        execute(m.postId, FlagPossibleDuplicate(PostId.unsafe(matchedId), now()))
      case None => Future.successful(Done)
    }

  /**
   * Publish the auto-tag job for the just-activated post. Best-effort like [[bestEffortFlag]]: a
   * publish failure (Hermes momentarily down) degrades to "not tagged" rather than failing
   * `onProcessed` and wedging the job in redelivery. Skips silently when there is no `sample`
   * derivative to tag.
   *
   * Caveat: this runs inside the `once(jobId)` guard, so a recovered publish failure still marks
   * the job applied — a redelivered `MediaProcessed` will NOT re-attempt the publish. A `TagJob`
   * lost to a Hermes-publish failure is therefore permanent (the post stays active-and-unreviewed),
   * unlike an Argus outage where Hermes buffers the already-published job. Acceptable while
   * auto-tagging is best-effort; a durable retry would move the publish out of the guard (at the
   * cost of duplicate jobs on redelivery) or drive it off the read model.
   */
  private def bestEffortPublishTagJob(m: MediaProcessed): Future[Done] =
    tagJobOf(m) match
      case None =>
        log.debug("no sample derivative for post {} (job {}); skipping tag-job", m.postId, m.jobId)
        Future.successful(Done)
      case Some(job) =>
        tagJobs
          .publish(job)
          .map(_ => Done)
          .recover { case NonFatal(e) =>
            log.warn(
              "tag-job publish failed for post {} (job {}): {}",
              m.postId,
              m.jobId,
              e.getMessage
            )
            Done
          }

  /**
   * Build the `TagJob` from the processed result: the `sample` derivative's Apollo ref (Argus reads
   * the small sample, never the original) and a coarse media type (video when a duration is
   * present, else image). `None` when no sample derivative was produced.
   */
  private def tagJobOf(m: MediaProcessed): Option[TagJob] =
    m.derivatives
      .find(_.kind == "sample")
      .flatMap(_.ref)
      .map(ref => TagJob(postId = m.postId, sample = Some(ref), mediaType = mediaTypeOf(m)))

  private def mediaTypeOf(m: MediaProcessed): String =
    if m.metadata.flatMap(_.durationSeconds).isDefined then "video" else "image"

  def onFailed(m: MediaFailed): Future[Unit] =
    once(m.jobId) {
      execute(m.postId, MarkFailed(reason(m), now()))
    }

  /** Apply `body` only if `jobId` has not been applied before; mark it applied on success. */
  private def once(jobId: String)(body: => Future[Done]): Future[Unit] =
    processedJobs.isApplied(jobId).flatMap {
      case true => Future.unit
      case false => body.flatMap(_ => processedJobs.markApplied(jobId))
    }

  private def execute(postId: String, command: PostCommand): Future[Done] =
    postFor(postId).askWithStatus(PostEntity.Execute(command, _))

  /**
   * Validate the wire metadata into domain `Dimensions`. `durationSeconds` (seconds, Double) rounds
   * to the domain's millisecond `Option[Long]`. Absent metadata, or dimensions the domain rejects
   * (e.g. non-positive extents), yield a `Left` so the caller fails rather than fabricating a
   * value.
   */
  private def dimensionsOf(m: MediaProcessed): Either[String, Dimensions] =
    m.metadata match
      case None => Left(s"MediaProcessed for job ${m.jobId} has no metadata")
      case Some(meta) =>
        Dimensions
          .from(meta.width, meta.height, meta.durationSeconds.map(s => Math.round(s * 1000)))
          .left
          .map(_.message)

  /** Each wire derivative → a domain `Derivative` whose `ref` is `<bucket>/<object>`. */
  private def derivatives(m: MediaProcessed): Vector[Derivative] =
    m.derivatives.toVector.map { d =>
      val ref = d.ref.map(r => s"${r.bucket}/${r.`object`}").getOrElse("")
      Derivative(d.kind, ref)
    }

  /** Prefer the error message, then the code, then a generic fallback. */
  private def reason(m: MediaFailed): String =
    m.error match
      case Some(e) if e.message.nonEmpty => e.message
      case Some(e) if e.code.nonEmpty => e.code
      case _ => "processing failed"

  private def now(): Instant = Instant.now()
