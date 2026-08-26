package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The self-documenting surface: the OpenAPI spec is a classpath resource served verbatim, and
 * `/docs` hosts a Swagger UI page whose spec URL is RELATIVE (so it works both direct and through
 * the artemis-ui BFF prefix).
 */
final class DocsRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  private val routes = DocsRoutes.fromResources().routes

  "GET /openapi.yaml" should {
    "serve the spec with the endpoints of record present" in {
      Get("/openapi.yaml") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("openapi: 3.0")
        // A sentinel per surface, so a spec that drifts out of the resource fails loudly here.
        body should include("/posts:")
        body should include("/pools/{id}/order:")
        body should include("/saved-searches/{name}/results:")
        body should include("/media/{md5}/{variant}:")
        body should include("/admin/gc/orphan-sweep:")
      }
    }
  }

  "GET /docs" should {
    "serve the Swagger UI page with a RELATIVE spec url" in {
      Get("/docs") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        contentType.mediaType.isText shouldBe true
        val body = responseAs[String]
        body should include("swagger-ui")
        // Relative, not absolute — must survive the BFF's /api/artemis prefix.
        body should include("url: \"openapi.yaml\"")
      }
    }
  }
