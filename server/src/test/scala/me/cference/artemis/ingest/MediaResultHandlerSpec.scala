package me.cference.artemis.ingest

import codex.messages.v1.{
  Derivative as WireDerivative,
  JobError,
  MediaFailed,
  MediaMetadata,
  MediaProcessed,
  ObjectRef
}
import me.cference.artemis.domain.PostCommand.CreatePost
import me.cference.artemis.domain.{Filetype, Md5, PostId, PostState}
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.RecipientRef
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout
import com.typesafe.config.ConfigFactory
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
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
