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

import java.nio.file.{Files, Path}
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

  /** Captures the last put + streamed bytes; succeeds. `chunkCount` proves it arrived streamed. */
  final private class RecordingUploader extends ObjectUploader:
    val lastHeader = new AtomicReference[Option[PutHeader]](None)
    val lastBytes = new AtomicReference[ByteString](ByteString.empty)
    val chunkCount = new AtomicReference[Int](0)
    def put(header: PutHeader, chunks: Source[ByteString, ?]): Future[PutObjectResponse] =
      chunks
        .runFold((ByteString.empty, 0)) { case ((acc, count), chunk) =>
          (acc ++ chunk, count + 1)
        }(Materializer(testKit.system))
        .map { case (bytes, count) =>
          lastHeader.set(Some(header))
          lastBytes.set(bytes)
          chunkCount.set(count)
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

  /** True when no `.part` was left behind — the leak this fix must not reintroduce. */
  private def spoolIsEmpty(directory: Path): Boolean =
    val stream = Files.list(directory)
    try stream.count() == 0L
    finally stream.close()

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

    "spool the body to disk instead of buffering it, and still compute the right md5" in {
      // The spec requires streaming "while computing the md5". The regression this guards is a
      // body folded into one ByteString first: survivable for an image, fatal for a 2 GiB video.
      val spool = Files.createTempDirectory("artemis-spool-stream")
      val uploader = new RecordingUploader
      val service =
        new UploadService(
          uploader,
          new RecordingPublisher,
          postFor,
          genId = () => "up-streamed",
          spoolDirectory = spool
        )

      // Many chunks, deliberately bigger than FileIO's read chunk size so the put is re-chunked.
      val chunk = ByteString("x" * 4096)
      val whole = (1 to 64).foldLeft(ByteString.empty)((acc, _) => acc ++ chunk)

      service
        .upload(Source(List.fill(64)(chunk)), contentType = "video/mp4", mediaType = "video")
        .futureValue

      // The md5 over the whole body is right even though it was never fully in memory here...
      uploader.lastHeader.get().map(_.expectedMd5) shouldBe Some(md5Hex(whole))
      // ...the bytes Apollo received are byte-identical...
      uploader.lastBytes.get() shouldBe whole
      // ...and they arrived as a stream of many chunks, not one buffered ByteString.
      uploader.chunkCount.get() should be > 1

      spoolIsEmpty(spool) shouldBe true
    }

    "key a video upload content-addressed with the right extension" in {
      val uploader = new RecordingUploader
      val service =
        new UploadService(uploader, new RecordingPublisher, postFor, genId = () => "up-video")

      val bytes = ByteString("fake mp4 bytes")
      service.upload(Source.single(bytes), "video/mp4", "video").futureValue

      val md5 = md5Hex(bytes)
      uploader.lastHeader.get().map(_.`object`) shouldBe
        Some(s"originals/${md5.take(2)}/$md5.mp4")
    }

    "delete the spooled file after a successful upload" in {
      val spool = Files.createTempDirectory("artemis-spool-success")
      val service =
        new UploadService(
          new RecordingUploader,
          new RecordingPublisher,
          postFor,
          genId = () => "up-cleanup-ok",
          spoolDirectory = spool
        )

      service.upload(Source.single(ByteString("bytes")), "image/png", "image").futureValue

      spoolIsEmpty(spool) shouldBe true
    }

    "delete the spooled file when the put fails" in {
      // Otherwise a rejected multi-gigabyte upload leaks a .part until someone notices the disk.
      val spool = Files.createTempDirectory("artemis-spool-failure")
      val service =
        new UploadService(
          new RejectingUploader,
          new RecordingPublisher,
          postFor,
          genId = () => "up-cleanup-fail",
          spoolDirectory = spool
        )

      service.upload(Source.single(ByteString("bad")), "image/png", "image").failed.futureValue

      spoolIsEmpty(spool) shouldBe true
    }

    "delete the spooled file when the body itself fails mid-stream" in {
      val spool = Files.createTempDirectory("artemis-spool-abort")
      val uploader = new RecordingUploader
      val publisher = new RecordingPublisher
      val service =
        new UploadService(
          uploader,
          publisher,
          postFor,
          genId = () => "up-cleanup-abort",
          spoolDirectory = spool
        )

      val broken =
        Source(List(ByteString("half"))).concat(Source.failed(new RuntimeException("client gone")))

      service.upload(broken, "image/png", "image").failed.futureValue

      spoolIsEmpty(spool) shouldBe true
      // A truncated body is never put, so no post and no job come of it.
      uploader.lastHeader.get() shouldBe None
      getState("up-cleanup-abort") shouldBe PostState.Empty
      publisher.published.get() shouldBe empty
    }

    "create the spool directory when it does not exist yet" in {
      val spool = Files.createTempDirectory("artemis-spool-parent").resolve("nested/uploads")
      val uploader = new RecordingUploader
      val service =
        new UploadService(
          uploader,
          new RecordingPublisher,
          postFor,
          genId = () => "up-spool-mkdir",
          spoolDirectory = spool
        )

      service.upload(Source.single(ByteString("bytes")), "image/png", "image").futureValue

      Files.isDirectory(spool) shouldBe true
      uploader.lastBytes.get() shouldBe ByteString("bytes")
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
