package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.tracing.CorrelationId
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.MDC

/**
 * Route coverage for the HTTP tracing wrapper (request-tracing capability): the `X-Correlation-Id`
 * header is returned on a normal completion, on an explicit error status, and on a 404 for an
 * unmatched path (the inner route is sealed below the response mapping). The edge is untrusted, so
 * a client-supplied `X-Correlation-Id` is IGNORED and a fresh id is minted (anti-injection), and
 * the minted id is visible in the MDC while the inner route runs.
 */
final class RequestTracingSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  // Don't let the runtime config's coordinated-shutdown kill the forked test JVM when the route
  // test's ActorSystem terminates (matches the other http route specs).
  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  private def cid: Option[String] =
    header(CorrelationId.HttpHeader).map(_.value)

  "RequestTracing.withCorrelationId" should {

    "return X-Correlation-Id on a successful response" in {
      val route = RequestTracing.withCorrelationId(path("ok")(get(complete(StatusCodes.OK))))
      Get("/ok") ~> route ~> check {
        status shouldBe StatusCodes.OK
        cid.map(_.length) shouldBe Some(12)
      }
    }

    "return X-Correlation-Id on an error response" in {
      val route =
        RequestTracing.withCorrelationId(path("boom")(complete(StatusCodes.InternalServerError)))
      Get("/boom") ~> route ~> check {
        status shouldBe StatusCodes.InternalServerError
        cid should not be empty
      }
    }

    "return X-Correlation-Id on a 404 for an unmatched path" in {
      val route = RequestTracing.withCorrelationId(path("known")(complete(StatusCodes.OK)))
      Get("/unknown") ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        cid should not be empty
      }
    }

    "ignore a client-supplied X-Correlation-Id and mint a fresh one (anti-injection)" in {
      val injected = "client-supplied-evil-id"
      val route = RequestTracing.withCorrelationId(path("ok")(get(complete(StatusCodes.OK))))
      Get("/ok").addHeader(RawHeader(CorrelationId.HttpHeader, injected)) ~> route ~> check {
        status shouldBe StatusCodes.OK
        cid should not be empty
        cid should not be Some(injected) // the client's value never rides through
        cid.map(_.length) shouldBe Some(12) // a freshly minted token
      }
    }

    "expose the minted id in the MDC while the inner route runs, matching the echoed header" in {
      val route = RequestTracing.withCorrelationId(
        path("who")(get(complete(Option(MDC.get(CorrelationId.MdcKey)).getOrElse("none"))))
      )
      Get("/who") ~> route ~> check {
        status shouldBe StatusCodes.OK
        val inMdc = responseAs[String]
        inMdc should not be "none"
        cid shouldBe Some(inMdc) // the same id the handler logged under is echoed to the caller
      }
    }
  }
