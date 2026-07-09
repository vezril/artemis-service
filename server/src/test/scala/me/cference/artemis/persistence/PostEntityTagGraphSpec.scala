package me.cference.artemis.persistence

import me.cference.artemis.domain.*
import me.cference.artemis.domain.PostCommand.*
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.pattern.StatusReply
import com.typesafe.config.ConfigFactory
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import scala.concurrent.duration.DurationInt

/**
 * Verifies the write-path tag-graph seam (OpenSpec task 1.4): a `PostEntity` canonicalizes
 * `ChangeTags` against the injected [[TagGraph]] supplier — alias rewrite + transitive implication
 * expansion — before the `TagsChanged` event is journaled, and the defaulted `() => TagGraph.empty`
 * supplier leaves the existing (no-graph) path unchanged. Uses the in-memory persistence-testkit
 * journal (no Docker) and reads state back via `PostEntity.Get`.
 */
final class PostEntityTagGraphSpec
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
    with Matchers:

  private val now = Instant.parse("2026-07-09T00:00:00Z")
  private val md5 = Md5("d41d8cd98f00b204e9800998ecf8427e")
  private val filetype = Filetype("image/png")
  private val phash = Phash("f00dcafe")
  private val dimensions = Dimensions.unsafe(1920, 1080, Some(0L))
  private val derivatives = Vector(Derivative("thumbnail", "blob://thumb"))

  private val graph = TagGraph(
    aliases = Map(Tag.unsafe("catgirl") -> Tag.unsafe("cat_girl")),
    implications = Map(Tag.unsafe("cat_girl") -> Set(Tag.unsafe("animal_ears")))
  )

  private def execute(ref: org.apache.pekko.actor.typed.ActorRef[PostEntity.Command])(
      cmd: PostCommand
  ): Unit =
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    ref ! PostEntity.Execute(cmd, probe.ref)
    val _ = probe.receiveMessage(3.seconds).isSuccess shouldBe true

  private def tagsOf(ref: org.apache.pekko.actor.typed.ActorRef[PostEntity.Command]): Set[Tag] =
    val probe = testKit.createTestProbe[PostState]()
    ref ! PostEntity.Get(probe.ref)
    probe.receiveMessage(3.seconds) match
      case PostState.Active(_, _, content) => content.tags
      case other => fail(s"expected Active, got $other")

  private def activePost(
      id: String,
      tagGraph: () => TagGraph
  ): org.apache.pekko.actor.typed.ActorRef[PostEntity.Command] =
    val postId = PostId.unsafe(id)
    val ref = testKit.spawn(PostEntity(postId, tagGraph))
    execute(ref)(CreatePost(postId, md5, filetype, now))
    execute(ref)(RecordProcessed(dimensions, derivatives, phash, now))
    ref

  "PostEntity ChangeTags with an injected tag graph" should {

    "canonicalize tags against the supplied graph (alias rewrite + implication expansion)" in {
      val ref = activePost("tg-1", () => graph)
      execute(ref)(ChangeTags(Set(Tag.unsafe("catgirl")), now))

      tagsOf(ref) shouldBe Set(Tag.unsafe("cat_girl"), Tag.unsafe("animal_ears"))
    }

    "leave tags un-rewritten under the default empty-graph supplier" in {
      val postId = PostId.unsafe("tg-2")
      val ref = testKit.spawn(PostEntity(postId)) // default () => TagGraph.empty
      execute(ref)(CreatePost(postId, md5, filetype, now))
      execute(ref)(RecordProcessed(dimensions, derivatives, phash, now))
      execute(ref)(ChangeTags(Set(Tag.unsafe("catgirl")), now))

      tagsOf(ref) shouldBe Set(Tag.unsafe("catgirl"))
    }
  }
