package me.cference.artemis.ingest

import apollostorage.grpc.{PutHeader, PutObjectResponse}
import codex.messages.v1.ProcessMediaJob
import com.typesafe.config.ConfigFactory
import io.grpc.{Status, StatusRuntimeException}
import me.cference.artemis.domain.{PostId, PostState}
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.RecipientRef
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.{ByteString, Timeout}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * Unit tests for the upload write path (OpenSpec task 4.1) against the Artemis-owned ports and
 * in-memory fakes — no real Apollo/Hermes. Uses the persistence-testkit journal (no Docker); a post
 * created by the service is read back via `PostEntity.Get` on the same memoized actor.
 */
final class UploadServiceSpec
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

  // Memoized one-actor-per-id factory, so a write and a later Get share the same live actor.
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

  private def getState(id: String): PostState =
    val probe = testKit.createTestProbe[PostState]()
    postFor(id) ! PostEntity.Get(probe.ref)
    probe.receiveMessage()

  /** Captures the last put + streamed bytes; succeeds. */
  final private class RecordingUploader extends ObjectUploader:
    val lastHeader = new AtomicReference[Option[PutHeader]](None)
    val lastBytes = new AtomicReference[ByteString](ByteString.empty)
    def put(header: PutHeader, chunks: Source[ByteString, ?]): Future[PutObjectResponse] =
      chunks
        .runFold(ByteString.empty)(_ ++ _)(Materializer(testKit.system))
        .map { bytes =>
          lastHeader.set(Some(header))
          lastBytes.set(bytes)
          PutObjectResponse(
            generation = 1L,
            crc32C = "",
            md5 = header.expectedMd5,
            size = bytes.size.toLong
          )
        }(testKit.system.executionContext)

  /** Fails every put with a checksum/precondition rejection, standing in for Apollo's abort. */
  final private class RejectingUploader extends ObjectUploader:
    def put(header: PutHeader, chunks: Source[ByteString, ?]): Future[PutObjectResponse] =
      Future.failed(new StatusRuntimeException(Status.FAILED_PRECONDITION))

  /** Captures published jobs. */
  final private class RecordingPublisher extends MediaJobPublisher:
    val published = new AtomicReference[Vector[ProcessMediaJob]](Vector.empty)
    def publish(job: ProcessMediaJob): Future[Unit] =
      published.updateAndGet(_ :+ job)
      Future.successful(())

  private def md5Hex(bytes: ByteString): String =
    MessageDigest
      .getInstance("MD5")
      .digest(bytes.toArray)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  "UploadService.upload" should {

    "store the original content-addressed, create a pending post, publish a job, and return {postId, pending}" in {
      val uploader = new RecordingUploader
      val publisher = new RecordingPublisher
      val service = new UploadService(uploader, publisher, postFor, genId = () => "up-happy")

      val bytes = ByteString("some png bytes here")
      val expectedMd5 = md5Hex(bytes)
      val expectedKey = s"originals/${expectedMd5.take(2)}/$expectedMd5.png"

      val result =
        service
          .upload(Source.single(bytes), contentType = "image/png", mediaType = "image")
          .futureValue

      result.postId.value shouldBe "up-happy"
      result.status shouldBe "pending"

      // Content-addressed put with the md5 as the ingest checksum.
      uploader.lastHeader.get().map(_.bucket) shouldBe Some("media")
      uploader.lastHeader.get().map(_.`object`) shouldBe Some(expectedKey)
      uploader.lastHeader.get().map(_.expectedMd5) shouldBe Some(expectedMd5)
      uploader.lastBytes.get() shouldBe bytes

      // A pending post exists.
      getState("up-happy") shouldBe PostState.Pending(
        PostId.unsafe("up-happy"),
        me.cference.artemis.domain.Md5(expectedMd5),
        me.cference.artemis.domain.Filetype("image/png")
      )

      // Exactly one job, referencing the stored object and the new post.
      val jobs = publisher.published.get()
      jobs should have size 1
      jobs.head.postId shouldBe "up-happy"
      jobs.head.source.map(_.bucket) shouldBe Some("media")
      jobs.head.source.map(_.`object`) shouldBe Some(expectedKey)
      jobs.head.want should contain allOf ("thumb", "sample")
    }

    "abort on a checksum mismatch: no post created and no job published" in {
      val uploader = new RejectingUploader
      val publisher = new RecordingPublisher
      val service = new UploadService(uploader, publisher, postFor, genId = () => "up-abort")

      val failure =
        service.upload(Source.single(ByteString("bad")), "image/png", "image").failed.futureValue
      failure shouldBe a[StatusRuntimeException]

      getState("up-abort") shouldBe PostState.Empty
      publisher.published.get() shouldBe empty
    }
  }
