package me.cference.artemis.ingest

import codex.messages.v1.{TagSuggestion as WireSuggestion, TagSuggestions}
import com.typesafe.config.ConfigFactory
import me.cference.artemis.domain.PostCommand.{CreatePost, Delete, RecordProcessed}
import me.cference.artemis.domain.{
  Derivative,
  Dimensions,
  Filetype,
  Md5,
  Phash,
  PostId,
  PostState,
  Tag,
  TagGraph
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
import org.apache.pekko.util.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import scala.concurrent.duration.DurationInt

/**
 * Unit tests for the auto-tag consume path (auto-tagging spec, task 3.2): `TagSuggestions` from
 * Argus is alias-merged into the canonical vocabulary and recorded on an active post
 * (`RecordSuggestions`), flagging it for review — idempotently. A not-yet-active post rejects the
 * command so the message stays un-acked for redelivery. Uses the persistence-testkit journal (no
 * Docker) and reads back state via `PostEntity.Get`.
 */
final class TagSuggestionHandlerSpec
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

  // `outdoor` (a model's label) aliases to canonical `outdoors`.
  private val graph = TagGraph(
    aliases = Map(Tag.unsafe("outdoor") -> Tag.unsafe("outdoors")),
    implications = Map.empty
  )
  private def handler = new TagSuggestionHandler(postFor, () => graph)

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

  private def send(id: String, cmd: me.cference.artemis.domain.PostCommand): Unit =
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    postFor(id) ! PostEntity.Execute(cmd, probe.ref)
    val _ = probe.receiveMessage()

  private def activate(id: String): Unit =
    send(id, CreatePost(PostId.unsafe(id), Md5("abc"), Filetype("image/png"), Instant.now()))
    send(
      id,
      RecordProcessed(
        Dimensions.unsafe(800, 600, None),
        Vector(Derivative("sample", "r")),
        Phash("p"),
        Instant.now()
      )
    )

  private def getState(id: String): PostState =
    val probe = testKit.createTestProbe[PostState]()
    postFor(id) ! PostEntity.Get(probe.ref)
    probe.receiveMessage()

  private def suggestions(postId: String, tags: (String, Double, String)*): TagSuggestions =
    TagSuggestions(
      postId = postId,
      suggestions = tags.map((t, c, s) => WireSuggestion(tag = t, confidence = c, source = s)),
      status = "ok"
    )

  private def contentOf(id: String) = getState(id) match
    case PostState.Active(_, _, content) => content
    case other => fail(s"expected Active, got $other")

  "TagSuggestionHandler.onSuggestions" should {

    "alias-merge and record suggestions, flagging the post for review" in {
      activate("ts1")
      // outdoors(wd,.9) + outdoor(ram,.8) collapse to canonical outdoors@.9; tree stays.
      handler
        .onSuggestions(
          suggestions("ts1", ("outdoors", 0.9, "wd"), ("outdoor", 0.8, "ram"), ("tree", 0.7, "wd"))
        )
        .futureValue

      val content = contentOf("ts1")
      content.needsReview shouldBe true
      content.suggestions.map(_.tag) shouldBe Vector(Tag.unsafe("outdoors"), Tag.unsafe("tree"))
      content.suggestions.head.confidence shouldBe 0.9
      content.tags shouldBe empty // suggestions are not applied tags
    }

    "be idempotent — redelivering the same suggestions re-records the same set" in {
      activate("ts2")
      val msg = suggestions("ts2", ("tree", 0.7, "wd"))
      handler.onSuggestions(msg).futureValue
      handler.onSuggestions(msg).futureValue // redelivery

      contentOf("ts2").suggestions.map(_.tag) shouldBe Vector(Tag.unsafe("tree"))
    }

    "record nothing (a clean no-op) when Argus returns no usable suggestions" in {
      activate("ts3")
      handler.onSuggestions(suggestions("ts3")).futureValue // empty
      contentOf("ts3").needsReview shouldBe false
    }

    "fail (leaving the message un-acked) when the post is not yet active" in {
      // Created but not processed — still Pending, a TRANSIENT miss → re-fail for redelivery.
      send(
        "ts4",
        CreatePost(PostId.unsafe("ts4"), Md5("abc"), Filetype("image/png"), Instant.now())
      )
      handler.onSuggestions(suggestions("ts4", ("tree", 0.7, "wd"))).failed.futureValue
    }

    "ack-drop (not poison-loop) suggestions for a terminally deleted post" in {
      activate("ts5")
      send("ts5", Delete(Instant.now())) // now a Deleted tombstone — permanently not active
      // The record fails, but the handler must SUCCEED (ack + drop) rather than fail forever.
      handler.onSuggestions(suggestions("ts5", ("tree", 0.7, "wd"))).futureValue
    }
  }
