package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.domain.PostCommand.{CreatePost, RecordProcessed, RecordSuggestions}
import me.cference.artemis.domain.{
  Derivative,
  Dimensions,
  Filetype,
  Md5,
  Phash,
  PostCommand,
  PostId,
  PostState,
  SuggestedTag,
  Tag
}
import me.cference.artemis.persistence.PostEntity
import me.cference.artemis.projection.{ReviewItem, SuggestionView}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.RecipientRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * Route tests for the auto-tag review API (auto-tagging spec, tasks 5.1/5.2). `GET /review` renders
 * the queue from an injected read fn; `POST /posts/{id}/review` drives a REAL `PostEntity` on the
 * in-memory journal (no Docker) through accept/reject — proving the end-to-end aggregate flow:
 * activate → suggestions recorded → accept applies the chosen tags and clears review.
 */
final class ReviewRoutesSpec
    extends AnyWordSpec
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfterAll:

  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(PersistenceTestKitPlugin.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
      .withFallback(ConfigFactory.load())

  private val testKit: ActorTestKit = ActorTestKit(system.toTyped)
  private given Timeout = Timeout(3.seconds)
  private given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system

  override def afterAll(): Unit = testKit.shutdownTestKit()

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

  /** Drive a post to active-and-flagged with one pending suggestion. */
  private def activateAndFlag(id: String, tag: String): Unit =
    send(id, CreatePost(PostId.unsafe(id), Md5("abc"), Filetype("image/png"), Instant.now()))
    send(
      id,
      RecordProcessed(
        Dimensions.unsafe(8, 6, None),
        Vector(Derivative("sample", "r")),
        Phash("p"),
        Instant.now()
      )
    )
    send(id, RecordSuggestions(Vector(SuggestedTag(Tag.unsafe(tag), 0.9, "wd")), Instant.now()))

  private def contentOf(id: String) = getState(id) match
    case PostState.Active(_, _, content) => content
    case other => fail(s"expected Active, got $other")

  private def getState(id: String): PostState =
    val probe = testKit.createTestProbe[PostState]()
    postFor(id) ! PostEntity.Get(probe.ref)
    probe.receiveMessage()

  private def json(body: String) = HttpEntity(ContentTypes.`application/json`, body)

  private def routesWith(queue: Int => Future[Seq[ReviewItem]]): Route =
    new ReviewRoutes(queue, postFor).routes

  private val emptyQueue: Int => Future[Seq[ReviewItem]] = _ => Future.successful(Seq.empty)

  "GET /review" should {

    "render the queue with each post's pending suggestions" in {
      val queue: Int => Future[Seq[ReviewItem]] = _ =>
        Future.successful(
          Seq(ReviewItem("p1", Vector(SuggestionView("cat_girl", 0.9, "wd"))))
        )
      Get("/review") ~> routesWith(queue) ~> check {
        status shouldBe StatusCodes.OK
        val b = responseAs[String]
        b should include("\"postId\":\"p1\"")
        b should include("\"tag\":\"cat_girl\"")
        b should include("\"confidence\":0.9")
      }
    }

    "return an empty queue as an empty list" in {
      Get("/review") ~> routesWith(emptyQueue) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"posts\":[]")
      }
    }
  }

  "POST /posts/{id}/review" should {

    "accept chosen suggestions — apply them and clear review" in {
      activateAndFlag("rv1", "cat_girl")
      Post("/posts/rv1/review", json("""{"accept":["cat_girl"]}""")) ~>
        routesWith(emptyQueue) ~> check {
          status shouldBe StatusCodes.OK
        }
      val content = contentOf("rv1")
      content.tags shouldBe Set(Tag.unsafe("cat_girl"))
      content.needsReview shouldBe false
      content.suggestions shouldBe empty
    }

    "reject all (empty accept) — clear review without applying tags" in {
      activateAndFlag("rv2", "cat_girl")
      Post("/posts/rv2/review", json("""{"accept":[]}""")) ~> routesWith(emptyQueue) ~> check {
        status shouldBe StatusCodes.OK
      }
      val content = contentOf("rv2")
      content.tags shouldBe empty
      content.needsReview shouldBe false
    }

    "treat an absent accept field as reject-all" in {
      activateAndFlag("rv3", "cat_girl")
      Post("/posts/rv3/review", json("""{}""")) ~> routesWith(emptyQueue) ~> check {
        status shouldBe StatusCodes.OK
      }
      contentOf("rv3").needsReview shouldBe false
    }

    "reject a malformed tag with 400 and issue no command" in {
      activateAndFlag("rv4", "cat_girl")
      Post("/posts/rv4/review", json("""{"accept":["  "]}""")) ~> routesWith(emptyQueue) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
      contentOf("rv4").needsReview shouldBe true // untouched
    }
  }
