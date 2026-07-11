package me.cference.artemis.ingest

import apollostorage.grpc.PutObjectResponse
import codex.messages.v1.ProcessMediaJob
import com.typesafe.config.ConfigFactory
import me.cference.artemis.domain.PostCommand.{ChangeTags, CreatePost, Delete, RecordProcessed}
import me.cference.artemis.domain.{
  Derivative,
  Dimensions,
  Filetype,
  Md5,
  Phash,
  PostCommand,
  PostId,
  PostState,
  Tag
}
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.RecipientRef
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.{ByteString, Timeout}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * Deduplicated-ingest tests (deduplicated-ingest spec, tasks 1.1/1.2): a duplicate md5 merges into
 * the existing post (no new post), a soft-deleted match restores, and an absent/purged md5 creates
 * fresh. Drives real `PostEntity`s on the in-memory journal; the md5 lookup is a fake in place of
 * `ReadModelRepository.findByMd5`.
 */
final class UploadDedupSpec
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

  private def send(id: String, cmd: PostCommand): Unit =
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    postFor(id) ! PostEntity.Execute(cmd, probe.ref)
    val _ = probe.receiveMessage()

  private def getState(id: String): PostState =
    val probe = testKit.createTestProbe[PostState]()
    postFor(id) ! PostEntity.Get(probe.ref)
    probe.receiveMessage()

  private def contentOf(id: String) = getState(id) match
    case PostState.Active(_, _, content) => content
    case other => fail(s"expected Active, got $other")

  /** Bring a post to active with the given md5 + initial tags. */
  private def seedActive(id: String, md5: String, tags: Set[String]): Unit =
    send(id, CreatePost(PostId.unsafe(id), Md5(md5), Filetype("image/png"), Instant.now()))
    send(
      id,
      RecordProcessed(
        Dimensions.unsafe(8, 6, None),
        Vector(Derivative("sample", "r")),
        Phash("p"),
        Instant.now()
      )
    )
    if tags.nonEmpty then send(id, ChangeTags(tags.map(Tag.unsafe), Instant.now()))

  /** An uploader that always succeeds (content-addressed re-put is a no-op). */
  private val okUploader: ObjectUploader = (header, _) =>
    Future.successful(PutObjectResponse(1L, "", header.expectedMd5, 0L))

  private def recordingPublisher(): (MediaJobPublisher, AtomicReference[Vector[ProcessMediaJob]]) =
    val seen = new AtomicReference[Vector[ProcessMediaJob]](Vector.empty)
    val pub: MediaJobPublisher = job =>
      seen.updateAndGet(_ :+ job)
      Future.successful(())
    (pub, seen)

  private def md5Of(s: String): String =
    MessageDigest.getInstance("MD5").digest(s.getBytes).map(b => f"${b & 0xff}%02x").mkString

  private def serviceWith(
      lookup: String => Future[Option[(String, String)]],
      publisher: MediaJobPublisher,
      newId: String
  ): UploadService =
    new UploadService(okUploader, publisher, postFor, lookup, genId = () => newId)

  "UploadService dedup" should {

    "merge metadata into a live duplicate — no new post" in {
      val bytes = ByteString("dup-image")
      val md5 = md5Of("dup-image")
      seedActive("live-1", md5, Set("1girl"))
      val (pub, seen) = recordingPublisher()
      val service =
        serviceWith(_ => Future.successful(Some(("live-1", "active"))), pub, "should-not-create")

      val result = service
        .upload(Source.single(bytes), "image/png", "image", tags = Set(Tag.unsafe("cat_ears")))
        .futureValue

      result.postId shouldBe PostId.unsafe("live-1") // existing post, not a new one
      contentOf("live-1").tags shouldBe Set(Tag.unsafe("1girl"), Tag.unsafe("cat_ears")) // union
      getState("should-not-create") shouldBe PostState.Empty // no fresh post
      seen.get() shouldBe empty // no processing job for a dedup
    }

    "restore a soft-deleted match and merge" in {
      val bytes = ByteString("deleted-image")
      val md5 = md5Of("deleted-image")
      seedActive("del-1", md5, Set("keep"))
      send("del-1", Delete(Instant.now()))
      getState("del-1") shouldBe a[PostState.Deleted]

      val (pub, _) = recordingPublisher()
      val service =
        serviceWith(_ => Future.successful(Some(("del-1", "deleted"))), pub, "should-not-create")

      val result = service
        .upload(Source.single(bytes), "image/png", "image", tags = Set(Tag.unsafe("added")))
        .futureValue

      result.postId shouldBe PostId.unsafe("del-1")
      result.status shouldBe "active"
      val content = contentOf("del-1") // restored to active
      content.tags shouldBe Set(Tag.unsafe("keep"), Tag.unsafe("added"))
    }

    "create a fresh post when the md5 is absent (or purged)" in {
      val (pub, seen) = recordingPublisher()
      val service = serviceWith(_ => Future.successful(None), pub, "fresh-1")

      val result =
        service.upload(Source.single(ByteString("brand-new")), "image/png", "image").futureValue

      result.postId shouldBe PostId.unsafe("fresh-1")
      result.status shouldBe "pending"
      getState("fresh-1") shouldBe a[PostState.Pending]
      seen.get() should have size 1 // a fresh upload enqueues processing
    }
  }
