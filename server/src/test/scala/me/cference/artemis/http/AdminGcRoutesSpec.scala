package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.gc.OrphanSweepService
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

/**
 * Route tests for the GC admin surface (admin-gc spec): `POST /admin/gc/orphan-sweep` passes the
 * body's `dryRun` (defaulting to true when absent) to the injected sweep and returns its counts,
 * and `POST /admin/gc/purge-deleted` returns the injected pass's purged count. Both seams are
 * fakes.
 */
final class AdminGcRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  private val lastDryRun = new AtomicReference[Option[Boolean]](None)

  // The sweep echoes the dryRun it was called with (deleted=0 on a dry-run, 1 otherwise) so the
  // route's dryRun resolution is observable in the response.
  private val sweep: Boolean => Future[OrphanSweepService.SweepResult] =
    dryRun =>
      lastDryRun.set(Some(dryRun))
      Future.successful(OrphanSweepService.SweepResult(3, 1, if dryRun then 0 else 1))

  private val purgeDeleted: () => Future[Int] = () => Future.successful(2)

  private def routes: Route = AdminGcRoutes(sweep, purgeDeleted).routes

  private def json(body: String) = HttpEntity(ContentTypes.`application/json`, body)

  "POST /admin/gc/orphan-sweep" should {

    "run a dry-run when the body says dryRun:true" in {
      lastDryRun.set(None)
      Post("/admin/gc/orphan-sweep", json("""{"dryRun":true}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"scanned\":3")
        body should include("\"orphans\":1")
        body should include("\"deleted\":0")
      }
      lastDryRun.get() shouldBe Some(true)
    }

    "run a real sweep when the body says dryRun:false" in {
      lastDryRun.set(None)
      Post("/admin/gc/orphan-sweep", json("""{"dryRun":false}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"orphans\":1")
        body should include("\"deleted\":1")
      }
      lastDryRun.get() shouldBe Some(false)
    }

    "default to a dry-run when there is no body" in {
      lastDryRun.set(None)
      Post("/admin/gc/orphan-sweep") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"deleted\":0")
      }
      lastDryRun.get() shouldBe Some(true)
    }

    "default to a dry-run (never a destructive sweep) on a malformed body" in {
      lastDryRun.set(None)
      Post("/admin/gc/orphan-sweep", json("not valid json")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"deleted\":0")
      }
      lastDryRun.get() shouldBe Some(true) // malformed input must not trigger a real delete
    }
  }

  "POST /admin/gc/purge-deleted" should {
    "return the purged count from the pass" in {
      Post("/admin/gc/purge-deleted") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"purged\":2")
      }
    }
  }
