package me.cference.artemis.ingest

import codex.messages.v1.{
  Derivative as WireDerivative,
  JobError,
  MediaFailed,
  MediaMetadata,
  MediaProcessed,
  ObjectRef,
  TagJob
}
import com.typesafe.config.ConfigFactory
import me.cference.artemis.domain.PostCommand.CreatePost
import me.cference.artemis.domain.{Filetype, Md5, PostId, PostState}
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.RecipientRef
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.apache.pekko.util.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * Unit tests for the consume path (OpenSpec task 4.2): `MediaProcessed` drives a pending post to
 * active, `MediaFailed` to failed, and both are idempotent per `jobId` via [[ProcessedJobs]]. Uses
 * the persistence-testkit journal (no Docker) and reads back state via `PostEntity.Get`.
 */
final class MediaResultHandlerSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("""
          pekko.coordinated-shutdown.exit-jvm = off
          pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
        """)
        .withFallback(PersistenceTestKitPlugin.config)
        .withFallback(PersistenceTestKitSnapshotPlugin.config)
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(100, Millis))

  private given Timeout = Timeout(3.seconds)

  @volatile private var posts: Map[String, RecipientRef[PostEntity.Command]] = Map.empty
  private def postFor(id: String): RecipientRef[PostEntity.Command] =
    synchronized {
      posts.getOrElse(
        id, {
          val ref = testKit.spawn(PostEntity(PostId.unsafe(id)))
          posts = posts.updated(id, ref)
          ref
        }
      )
    }

  private def createPending(id: String): Unit =
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    postFor(id) ! PostEntity.Execute(
      CreatePost(PostId.unsafe(id), Md5("abc"), Filetype("image/png"), Instant.now()),
      probe.ref
    )
    val _ = probe.receiveMessage()

  private def getState(id: String): PostState =
    val probe = testKit.createTestProbe[PostState]()
    postFor(id) ! PostEntity.Get(probe.ref)
    probe.receiveMessage()

  private def processed(jobId: String, postId: String, width: Int, height: Int): MediaProcessed =
    MediaProcessed(
      jobId = jobId,
      postId = postId,
      status = "ok",
      metadata = Some(MediaMetadata(width = width, height = height, md5 = "abc", filetype = "png")),
      phash = "ph-1",
      derivatives = Seq(
        WireDerivative(kind = "thumb", ref = Some(ObjectRef("media", s"$postId/thumb.webp")))
      ),
      specVersion = 1
    )

  /** A processed result carrying a `sample` derivative (what auto-tagging publishes to Argus). */
  private def processedWithSample(
      jobId: String,
      postId: String,
      duration: Option[Double] = None
  ): MediaProcessed =
    processed(jobId, postId, 800, 600).copy(
      metadata = Some(
        MediaMetadata(
          width = 800,
          height = 600,
          durationSeconds = duration,
          md5 = "abc",
          filetype = "png"
        )
      ),
      derivatives = Seq(
        WireDerivative(kind = "thumb", ref = Some(ObjectRef("media", s"$postId/thumb.webp"))),
        WireDerivative(kind = "sample", ref = Some(ObjectRef("media", s"$postId/sample.webp")))
      )
    )

  private def failed(jobId: String, postId: String, message: String): MediaFailed =
    MediaFailed(
      jobId = jobId,
      postId = postId,
      error = Some(JobError(code = "DECODE", message = message)),
      retriable = false
    )

  "MediaResultHandler.onProcessed" should {

    "record processing and activate a pending post" in {
      val jobs = new InMemoryProcessedJobs
      val handler = new MediaResultHandler(jobs, postFor)
      createPending("mp1")

      handler.onProcessed(processed("j-mp1", "mp1", 800, 600)).futureValue

      getState("mp1") match
        case PostState.Active(id, media, _) =>
          id shouldBe PostId.unsafe("mp1")
          media.dimensions.width shouldBe 800
          media.dimensions.height shouldBe 600
          media.phash.value shouldBe "ph-1"
          media.derivatives shouldBe Vector(
            me.cference.artemis.domain.Derivative("thumb", "media/mp1/thumb.webp")
          )
        case other => fail(s"expected Active, got $other")
      jobs.isApplied("j-mp1").futureValue shouldBe true
    }

    "be idempotent per jobId: redelivery of the same jobId is a no-op" in {
      val jobs = new InMemoryProcessedJobs
      val handler = new MediaResultHandler(jobs, postFor)
      createPending("mp2")

      handler.onProcessed(processed("j-mp2", "mp2", 800, 600)).futureValue
      // Redeliver the SAME jobId but with different dims — the guard must ignore it entirely.
      handler.onProcessed(processed("j-mp2", "mp2", 1920, 1080)).futureValue

      getState("mp2") match
        case PostState.Active(_, media, _) =>
          media.dimensions.width shouldBe 800
          media.dimensions.height shouldBe 600
        case other => fail(s"expected Active, got $other")
    }

    "flag the post as a possible duplicate when near-duplicate detection matches" in {
      val jobs = new InMemoryProcessedJobs
      val handler = new MediaResultHandler(jobs, postFor, new FakeNearDuplicates(Some("mp-match")))
      createPending("mpd1")

      handler.onProcessed(processed("j-mpd1", "mpd1", 800, 600)).futureValue

      getState("mpd1") match
        case PostState.Active(_, _, content) =>
          content.duplicateOf shouldBe Some(PostId.unsafe("mp-match"))
        case other => fail(s"expected Active, got $other")
    }

    "leave a unique post unflagged when near-duplicate detection finds no match" in {
      val jobs = new InMemoryProcessedJobs
      val handler = new MediaResultHandler(jobs, postFor, new FakeNearDuplicates(None))
      createPending("mpd2")

      handler.onProcessed(processed("j-mpd2", "mpd2", 800, 600)).futureValue

      getState("mpd2") match
        case PostState.Active(_, _, content) => content.duplicateOf shouldBe None
        case other => fail(s"expected Active, got $other")
    }

    "activate and complete even if near-duplicate detection fails (best-effort warning)" in {
      // The dup notice must never block ingest: a read-model outage degrades to "not flagged",
      // the post still activates, and the job is marked applied (no redelivery wedge).
      val jobs = new InMemoryProcessedJobs
      val handler = new MediaResultHandler(jobs, postFor, new FailingNearDuplicates)
      createPending("mpd3")

      handler.onProcessed(processed("j-mpd3", "mpd3", 800, 600)).futureValue // does NOT fail

      getState("mpd3") match
        case PostState.Active(_, _, content) => content.duplicateOf shouldBe None
        case other => fail(s"expected Active, got $other")
      jobs.isApplied("j-mpd3").futureValue shouldBe true
    }

    "fail a MediaProcessed with no metadata rather than activate a 0x0 post" in {
      val jobs = new InMemoryProcessedJobs
      val handler = new MediaResultHandler(jobs, postFor)
      createPending("mp3")

      val noMeta = processed("j-mp3", "mp3", 800, 600).copy(metadata = None)
      handler.onProcessed(noMeta).failed.futureValue shouldBe an[IllegalArgumentException]

      // Not activated, and the job is NOT marked applied (so a corrected redelivery can retry).
      getState("mp3") match
        case _: PostState.Pending => succeed
        case other => fail(s"expected Pending, got $other")
      jobs.isApplied("j-mp3").futureValue shouldBe false
    }
  }

  "MediaResultHandler auto-tag publish" should {

    "publish a TagJob for the sample derivative when a post activates" in {
      val jobs = new InMemoryProcessedJobs
      val tagJobs = new RecordingTagJobs
      val handler = new MediaResultHandler(jobs, postFor, NearDuplicates.none, tagJobs)
      createPending("tj1")

      handler.onProcessed(processedWithSample("j-tj1", "tj1")).futureValue

      val published = tagJobs.published
      published.map(_.postId) shouldBe Seq("tj1")
      published.head.sample.map(_.`object`) shouldBe Some("tj1/sample.webp")
      published.head.mediaType shouldBe "image" // no duration → image
    }

    "mark a TagJob for a post with a duration as video" in {
      val tagJobs = new RecordingTagJobs
      val handler =
        new MediaResultHandler(new InMemoryProcessedJobs, postFor, NearDuplicates.none, tagJobs)
      createPending("tj2")

      handler.onProcessed(processedWithSample("j-tj2", "tj2", duration = Some(12.0))).futureValue

      tagJobs.published.head.mediaType shouldBe "video"
    }

    "not publish when no sample derivative is present, but still activate" in {
      val tagJobs = new RecordingTagJobs
      val handler =
        new MediaResultHandler(new InMemoryProcessedJobs, postFor, NearDuplicates.none, tagJobs)
      createPending("tj3")

      // The default `processed` helper carries only a thumb derivative — no sample.
      handler.onProcessed(processed("j-tj3", "tj3", 800, 600)).futureValue

      tagJobs.published shouldBe empty
      getState("tj3") shouldBe a[PostState.Active]
    }

    "activate and complete even if the tag-job publish fails (best-effort)" in {
      val jobs = new InMemoryProcessedJobs
      val handler =
        new MediaResultHandler(jobs, postFor, NearDuplicates.none, new FailingTagJobs)
      createPending("tj4")

      handler.onProcessed(processedWithSample("j-tj4", "tj4")).futureValue // does NOT fail

      getState("tj4") shouldBe a[PostState.Active]
      jobs.isApplied("j-tj4").futureValue shouldBe true
    }
  }

  "MediaResultHandler.onFailed" should {

    "drive a pending post to failed" in {
      val jobs = new InMemoryProcessedJobs
      val handler = new MediaResultHandler(jobs, postFor)
      createPending("mf1")

      handler.onFailed(failed("j-mf1", "mf1", "unsupported codec")).futureValue

      getState("mf1") shouldBe PostState.Failed(
        PostId.unsafe("mf1"),
        Md5("abc"),
        Filetype("image/png"),
        "unsupported codec"
      )
      jobs.isApplied("j-mf1").futureValue shouldBe true
    }

    "be idempotent per jobId: redelivered onFailed is a successful no-op" in {
      val jobs = new InMemoryProcessedJobs
      val handler = new MediaResultHandler(jobs, postFor)
      createPending("mf2")

      handler.onFailed(failed("j-mf2", "mf2", "first reason")).futureValue
      // Redelivered same jobId — a clean no-op that keeps the first reason.
      handler.onFailed(failed("j-mf2", "mf2", "second reason")).futureValue

      getState("mf2") shouldBe PostState.Failed(
        PostId.unsafe("mf2"),
        Md5("abc"),
        Filetype("image/png"),
        "first reason"
      )
    }

    "stay a clean no-op on redelivery even if the dedup marker is lost (domain idempotence)" in {
      // Simulate a crash/restart that lost the applied-marker: the guard never short-circuits, so
      // correctness must come from the domain — MarkFailed on an already-Failed post is a no-op.
      val handler = new MediaResultHandler(new ForgetfulProcessedJobs, postFor)
      createPending("mf3")

      handler.onFailed(failed("j-mf3", "mf3", "first reason")).futureValue
      handler.onFailed(failed("j-mf3", "mf3", "second reason")).futureValue // no rejection

      getState("mf3") shouldBe PostState.Failed(
        PostId.unsafe("mf3"),
        Md5("abc"),
        Filetype("image/png"),
        "first reason"
      )
    }
  }

  /** A dedup store that never remembers — simulates a lost marker (crash/restart before mark). */
  final private class ForgetfulProcessedJobs extends ProcessedJobs:
    def isApplied(jobId: String): Future[Boolean] = Future.successful(false)
    def markApplied(jobId: String): Future[Unit] = Future.unit

  /** A near-duplicate port returning a fixed answer, exercising the flag/no-flag wiring. */
  /** A read model that always errors — stands in for a momentary dedup-query outage. */
  final private class FailingNearDuplicates extends NearDuplicates:
    def findNear(phash: String, excludeId: String): Future[Option[String]] =
      Future.failed(new RuntimeException("read model unavailable"))

  final private class FakeNearDuplicates(result: Option[String]) extends NearDuplicates:
    def findNear(phash: String, excludeId: String): Future[Option[String]] =
      Future.successful(result)

  /** A tag-job publisher that records what was published, for asserting the auto-tag wiring. */
  final private class RecordingTagJobs extends TagJobPublisher:
    @volatile private var jobs: Vector[TagJob] = Vector.empty
    def published: Seq[TagJob] = jobs
    def publish(job: TagJob): Future[Unit] =
      synchronized { jobs = jobs :+ job }
      Future.unit

  /** A publisher that always errors — stands in for a momentary Hermes outage. */
  final private class FailingTagJobs extends TagJobPublisher:
    def publish(job: TagJob): Future[Unit] =
      Future.failed(new RuntimeException("hermes unavailable"))
