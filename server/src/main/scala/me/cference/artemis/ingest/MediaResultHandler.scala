package me.cference.artemis.ingest

import codex.messages.v1.{MediaFailed, MediaProcessed}
import me.cference.artemis.domain.PostCommand.{FlagPossibleDuplicate, MarkFailed, RecordProcessed}
import me.cference.artemis.domain.{Derivative, Dimensions, Phash, PostCommand, PostId}
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.RecipientRef
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
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
    nearDuplicates: NearDuplicates = NearDuplicates.none
)(using system: ActorSystem[?], timeout: Timeout):

  private given scala.concurrent.ExecutionContext = system.executionContext
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
          execute(m.postId, RecordProcessed(dims, derivatives(m), Phash(m.phash), now()))
            .flatMap(_ => bestEffortFlag(m))
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
