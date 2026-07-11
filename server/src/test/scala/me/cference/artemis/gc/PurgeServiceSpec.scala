package me.cference.artemis.gc

import com.typesafe.config.ConfigFactory
import me.cference.artemis.domain.PostCommand.{CreatePost, Delete, RecordProcessed}
import me.cference.artemis.domain.{
  Derivative,
  Dimensions,
  Filetype,
  Md5,
  Phash,
  PostCommand,
  PostId,
  PostState
}
import me.cference.artemis.persistence.PostEntity
import me.cference.artemis.projection.PurgeTarget
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
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * Unit tests for the retention auto-purge (deletion-lifecycle spec, task 3.1): a due post is
 * permanently purged (`Deleted → Purged`) and its exact blob keys deleted 1:1; a post restored
 * since the work-list read is purge-rejected and keeps its blobs (the purge-first guard); with no
 * due posts, nothing is touched. The due-list is injected (fake for
 * `ReadModelRepository.softDeletedBefore`) and the blob store is a fake — no Docker/Apollo.
 */
final class PurgeServiceSpec
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

  private def seedActive(id: String, md5: String): Unit =
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

  private def softDelete(id: String, md5: String): Unit =
    seedActive(id, md5)
    send(id, Delete(Instant.now()))

  /** A blob store that records deletions (purge deletes exact keys, never lists). */
  private class FakeBlobStore extends BlobStore:
    val deleted = new ConcurrentLinkedQueue[String]()
    def list(prefix: String): Future[Seq[BlobRef]] = Future.successful(Seq.empty)
    def delete(key: String): Future[Unit] =
      deleted.add(key)
      Future.unit

  private val keys = Seq(
    "originals/ab/md5.png",
    "derivatives/ab/md5/thumb.webp",
    "derivatives/ab/md5/sample.webp"
  )

  "PurgeService.purgeDue" should {

    "permanently purge a due post and delete its exact blob keys 1:1" in {
      softDelete("pg-1", "abcdef00")
      getState("pg-1") shouldBe a[PostState.Deleted]

      val blobs = new FakeBlobStore
      val service =
        new PurgeService(
          _ => Future.successful(Seq(PurgeTarget("pg-1", keys))),
          blobs,
          postFor,
          30.days
        )

      service.purgeDue(Instant.now()).futureValue shouldBe 1
      blobs.deleted.asScala.toSet shouldBe keys.toSet
      getState("pg-1") shouldBe PostState.Purged(PostId.unsafe("pg-1"))
    }

    "NOT delete blobs when the post was restored since the work-list read (purge-first guard)" in {
      // The post is active (a re-upload restored it after it entered the due list). Purge must be
      // rejected and its blobs left intact — otherwise a live post loses its original.
      seedActive("race-1", "beefbeef")
      getState("race-1") shouldBe a[PostState.Active]

      val blobs = new FakeBlobStore
      val service = new PurgeService(
        _ => Future.successful(Seq(PurgeTarget("race-1", keys))),
        blobs,
        postFor,
        30.days
      )

      service.purgeDue(Instant.now()).futureValue shouldBe 0
      blobs.deleted shouldBe empty // the active post keeps its blobs
      getState("race-1") shouldBe a[PostState.Active]
    }

    "purge nothing (and delete no blobs) when no post is due" in {
      val blobs = new FakeBlobStore
      val service = new PurgeService(_ => Future.successful(Seq.empty), blobs, postFor, 30.days)
      service.purgeDue(Instant.now()).futureValue shouldBe 0
      blobs.deleted shouldBe empty
    }
  }
