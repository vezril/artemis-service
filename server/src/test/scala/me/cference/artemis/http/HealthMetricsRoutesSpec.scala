package me.cference.artemis.http

import me.cference.artemis.metrics.MetricsRegistry
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Route tests for the operational surface (service-runtime + service-metrics specs): `GET /health`
 * flips UP↔DOWN with the readiness flag, and `GET /metrics` renders valid Prometheus text carrying
 * the app counters. No DB, no cluster — asserts the wiring only.
 */
final class HealthMetricsRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  // Keep the shared runtime config's coordinated-shutdown from killing the forked test JVM.
  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  "GET /health" should {

    "return 200 UP when ready" in {
      val routes: Route = HealthRoutes("1.2.3", () => true)
      Get("/health") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"status\":\"UP\"")
        body should include("\"service\":\"artemis\"")
        body should include("\"version\":\"1.2.3\"")
      }
    }

    "return 503 DOWN once readiness is withdrawn" in {
      val ready = new AtomicBoolean(true)
      val routes: Route = HealthRoutes("1.2.3", () => ready.get())
      // Simulate the coordinated-shutdown readiness withdrawal.
      ready.set(false)
      Get("/health") ~> routes ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
        responseAs[String] should include("\"status\":\"DOWN\"")
      }
    }
  }

  "GET /metrics" should {

    "return 200 with valid Prometheus text including the app counters" in {
      val metrics = new MetricsRegistry("1.2.3", () => true)
      metrics.observeConsumePoll(2) // one poll that applied two messages
      metrics.observeConsumePollFailure() // one failed poll
      val routes: Route = MetricsRoutes(metrics)
      Get("/metrics") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        // App counters + build/ready gauges are present in the exposition.
        body should include("artemis_consume_polls_total 2.0") // one applied + one failed poll
        body should include("artemis_consume_messages_applied_total 2.0")
        body should include("artemis_consume_poll_failures_total 1.0")
        body should include("artemis_build_info")
        body should include("artemis_ready 1.0")
        // JVM/process default exports are registered too.
        body should include("jvm_")
      }
    }
  }
