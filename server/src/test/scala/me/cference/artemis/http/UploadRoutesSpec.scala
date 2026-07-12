package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.domain.PostId
import me.cference.artemis.ingest.UploadResult
import org.apache.pekko.http.scaladsl.model.{ContentType, HttpEntity, MediaTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

/**
 * Route test for the upload front door (catalog-api "HTTP upload endpoint"): `POST /uploads`
 * answers `201 { postId, status }`, derives the media class from the `Content-Type` (overridable by
 * `?mediaType=`), and maps an upstream failure to `502`. Backed by a FAKE upload function (no
 * Apollo/Hermes), so this asserts the wiring; the real pipeline is proven by
 * `RunnableServiceE2EIT`.
 */
final class UploadRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  private val mp4 = ContentType.Binary(MediaTypes.`video/mp4`)
  private val png = ContentType.Binary(MediaTypes.`image/png`)

  /**
   * A recording fake: captures the (contentType, mediaType) it was called with; always succeeds.
   */
  final private class FakeUpload(postId: String = "up-1"):
    val seen = new AtomicReference[Option[(String, String)]](None)
    val fn: (Source[ByteString, ?], String, String) => Future[UploadResult] =
      (_, ct, mt) =>
        seen.set(Some((ct, mt)))
        Future.successful(UploadResult(PostId.unsafe(postId), "pending"))

  private def routesOf(
      fn: (Source[ByteString, ?], String, String) => Future[UploadResult]
  ): Route = new UploadRoutes(fn).routes

  "POST /uploads" should {

    "store the bytes and return 201 with {postId, status:pending}" in {
      val fake = new FakeUpload("post-123")
      Post("/uploads", HttpEntity(png, ByteString("fake png bytes"))) ~> routesOf(
        fake.fn
      ) ~> check {
        status shouldBe StatusCodes.Created
        val body = responseAs[String]
        body should include("\"postId\":\"post-123\"")
        body should include("\"status\":\"pending\"")
      }
    }

    "derive the media class from the Content-Type (video/mp4 → video)" in {
      val fake = new FakeUpload()
      Post("/uploads", HttpEntity(mp4, ByteString("fake mp4"))) ~> routesOf(fake.fn) ~> check {
        status shouldBe StatusCodes.Created
      }
      fake.seen.get() shouldBe Some(("video/mp4", "video"))
    }

    "let ?mediaType= override the derived class" in {
      val fake = new FakeUpload()
      Post("/uploads?mediaType=clip", HttpEntity(mp4, ByteString("fake mp4"))) ~>
        routesOf(fake.fn) ~> check {
          status shouldBe StatusCodes.Created
        }
      fake.seen.get().map(_._2) shouldBe Some("clip")
    }

    "map an upstream (Apollo/Hermes) failure to 502" in {
      val failing: (Source[ByteString, ?], String, String) => Future[UploadResult] =
        (_, _, _) => Future.failed(new RuntimeException("apollo put failed"))
      Post("/uploads", HttpEntity(png, ByteString("bytes"))) ~> routesOf(failing) ~> check {
        status shouldBe StatusCodes.BadGateway
        responseAs[String] should include("apollo put failed")
      }
    }

    "reject an empty body with 400 (no orphan post)" in {
      val fake = new FakeUpload()
      Post("/uploads", HttpEntity(png, ByteString.empty)) ~> routesOf(fake.fn) ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("empty upload body")
      }
      fake.seen.get() shouldBe None // upload was never called
    }

    "default the media class to the top-level type for an octet-stream body" in {
      val fake = new FakeUpload()
      val octet = ContentType.Binary(MediaTypes.`application/octet-stream`)
      Post("/uploads", HttpEntity(octet, ByteString("raw"))) ~> routesOf(fake.fn) ~> check {
        status shouldBe StatusCodes.Created
      }
      fake.seen.get().map(_._2) shouldBe Some("application")
    }
  }
