package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.reprocess.ReprocessKind
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

/**
 * Route tests for `POST /reprocess` (reprocess-orchestration spec, task 5.1): a valid `{select,
 * kind}` calls the reprocess fn with the parsed kind and returns `{enqueued}`; an unknown kind is a
 * 400; a failed reprocess (e.g. a bad DSL query) is a 400. The reprocess fn is a fake.
 */
final class ReprocessRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  private val lastCall = new AtomicReference[Option[(String, ReprocessKind)]](None)

  private def routesWith(fn: (String, ReprocessKind) => Future[Int]): Route =
    new ReprocessRoutes(fn).routes

  private val ok: (String, ReprocessKind) => Future[Int] =
    (select, kind) =>
      lastCall.set(Some((select, kind)))
      Future.successful(7)

  private def json(body: String) = HttpEntity(ContentTypes.`application/json`, body)

  "POST /reprocess" should {

    "resolve the kind and return the enqueued count" in {
      Post("/reprocess", json("""{"select":"stale","kind":"derivatives"}""")) ~>
        routesWith(ok) ~> check {
          status shouldBe StatusCodes.OK
          responseAs[String] should include("\"enqueued\":7")
        }
      lastCall.get() shouldBe Some(("stale", ReprocessKind.Derivatives))
    }

    "parse the tags kind" in {
      Post("/reprocess", json("""{"select":"id:1284","kind":"tags"}""")) ~>
        routesWith(ok) ~> check(status shouldBe StatusCodes.OK)
      lastCall.get() shouldBe Some(("id:1284", ReprocessKind.Tags))
    }

    "reject an unknown kind with 400 (and never call the reprocess fn)" in {
      lastCall.set(None)
      Post("/reprocess", json("""{"select":"stale","kind":"everything"}""")) ~>
        routesWith(ok) ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      lastCall.get() shouldBe None
    }

    "map a failed reprocess (bad DSL query) to 400" in {
      val failing: (String, ReprocessKind) => Future[Int] =
        (_, _) => Future.failed(new IllegalArgumentException("bad query"))
      Post("/reprocess", json("""{"select":"is:borked","kind":"derivatives"}""")) ~>
        routesWith(failing) ~> check {
          status shouldBe StatusCodes.BadRequest
          responseAs[String] should include("bad query")
        }
    }
  }
