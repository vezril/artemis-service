package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.domain.{DomainError, DomainException}
import me.cference.artemis.gc.PurgeService
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.actor.typed.{Behavior, RecipientRef}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * Route tests for the per-post deletion-lifecycle admin surface (admin-deletion spec): soft-delete
 * and restore drive the aggregate through `onExecute` (happy path + `PostNotFound` → 404), and
 * purge wraps the injected `purgeNow` seam (purged/no-op bodies). The post entity is faked as a
 * tiny actor that acks or rejects with a typed `DomainException`; `purgeNow` is a plain fake — no
 * DB/Apollo.
 */
final class AdminDeletionRoutesSpec
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
      .withFallback(ConfigFactory.load())

  private val testKit: ActorTestKit = ActorTestKit(system.toTyped)

  private given Timeout = Timeout(3.seconds)
  private given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system

  /** A fake post entity that acks every `Execute`, replying `state` to `Get`. */
  private def acking: Behavior[PostEntity.Command] =
    Behaviors.receiveMessage {
      case PostEntity.Execute(_, replyTo) =>
        replyTo ! StatusReply.ack()
        Behaviors.same
      case PostEntity.Get(_) => Behaviors.same
    }

  /**
   * A fake post entity that rejects every `Execute` with `PostNotFound` (the unknown-post case).
   */
  private def rejecting: Behavior[PostEntity.Command] =
    Behaviors.receiveMessage {
      case PostEntity.Execute(_, replyTo) =>
        replyTo ! StatusReply.error(DomainException(DomainError.PostNotFound))
        Behaviors.same
      case PostEntity.Get(_) => Behaviors.same
    }

  private def postForAcking: String => RecipientRef[PostEntity.Command] =
    _ => testKit.spawn(acking)

  private def postForRejecting: String => RecipientRef[PostEntity.Command] =
    _ => testKit.spawn(rejecting)

  private val neverPurge: String => Future[PurgeService.PurgeOutcome] =
    _ => Future.successful(PurgeService.PurgeOutcome(false, 0))

  private def routes(
      postFor: String => RecipientRef[PostEntity.Command],
      purgeNow: String => Future[PurgeService.PurgeOutcome] = neverPurge
  ): Route =
    AdminDeletionRoutes(postFor, purgeNow).routes

  override def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  "DELETE /posts/{id}" should {
    "soft-delete an existing post and return 200 with status deleted" in {
      Delete("/posts/1284") ~> routes(postForAcking) ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"id\":\"1284\"")
        body should include("\"status\":\"deleted\"")
      }
    }

    "return 404 for an unknown post" in {
      Delete("/posts/9999") ~> routes(postForRejecting) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }

  "POST /posts/{id}/restore" should {
    "restore a soft-deleted post and return 200 with status active" in {
      Post("/posts/1284/restore") ~> routes(postForAcking) ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"id\":\"1284\"")
        body should include("\"status\":\"active\"")
      }
    }

    "return 404 for an unknown post" in {
      Post("/posts/9999/restore") ~> routes(postForRejecting) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }

  "POST /posts/{id}/purge" should {
    "report the blobs deleted on a confirmed purge" in {
      val purge: String => Future[PurgeService.PurgeOutcome] =
        _ => Future.successful(PurgeService.PurgeOutcome(true, 3))
      Post("/posts/1284/purge") ~> routes(postForAcking, purge) ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"id\":\"1284\"")
        body should include("\"purged\":true")
        body should include("\"blobsDeleted\":3")
      }
    }

    "report a no-op (purged:false, blobsDeleted:0) when nothing was purged" in {
      Post("/posts/1284/purge") ~> routes(postForAcking, neverPurge) ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"purged\":false")
        body should include("\"blobsDeleted\":0")
      }
    }

    "map a failing purge (e.g. a read-model error) to an error status, not a hang" in {
      val failing: String => Future[PurgeService.PurgeOutcome] =
        _ => Future.failed(new RuntimeException("read model unavailable"))
      Post("/posts/1284/purge") ~> routes(postForAcking, failing) ~> check {
        status shouldBe StatusCodes.InternalServerError
        responseAs[String] should include("read model unavailable")
      }
    }
  }
