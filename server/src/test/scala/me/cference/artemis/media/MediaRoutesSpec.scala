package me.cference.artemis.media

import codex.messages.v1.ObjectRef
import com.typesafe.config.ConfigFactory
import me.cference.artemis.http.MediaRoutes
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.Range
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future

/**
 * Route tests for the HTTP media gateway (OpenSpec catalog-api "HTTP media gateway over Apollo",
 * task 5.3 + the media-streaming part of 5.4). Runs against a fake [[MediaSource]] — no Apollo, no
 * Docker — mirroring `CatalogRoutesSpec`'s `Get(...) ~> routes ~> check {...}` style.
 */
final class MediaRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  // Don't let the shared runtime config's coordinated-shutdown kill the forked test JVM when the
  // route test's ActorSystem terminates — it races the sbt fork harness (spurious EOFException).
  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  /** A fake source that answers every `fetch` with the same fixed result. */
  private def sourceReturning(result: Option[MediaObject]): MediaSource =
    new MediaSource:
      def fetch(ref: ObjectRef): Future[Option[MediaObject]] = Future.successful(result)

  private def routesFor(result: Option[MediaObject]): Route =
    // The resolver seam is exercised by resolving via the convention fallback here; the
    // stored-ref-first production resolver is covered by ReadModelRepository's tests.
    MediaRoutes(
      sourceReturning(result),
      (md5, variant) => Future.successful(MediaResolver.resolve(md5, variant))
    ).routes

  /** A media object whose body is split into several chunks, to exercise cross-chunk slicing. */
  private def chunked(contentType: String, chunks: List[String]): MediaObject =
    val size = chunks.map(_.length).sum.toLong
    MediaObject(contentType, Some(size), Source(chunks.map(ByteString(_))))

  "GET /media/{md5}/{variant}" should {
    "return 404 when the source has no such object" in {
      Get("/media/deadbeef/thumb.webp") ~> routesFor(None) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "stream a thumbnail as 200 with its content type and Accept-Ranges" in {
      val media = chunked("image/webp", List("WEBP", "-body"))
      Get("/media/abc123/thumb.webp") ~> routesFor(Some(media)) ~> check {
        status shouldBe StatusCodes.OK
        contentType.value shouldBe "image/webp"
        header("Accept-Ranges").map(_.value) shouldBe Some("bytes")
        responseAs[String] shouldBe "WEBP-body"
      }
    }

    "return 206 with Content-Range and the sliced body for a closed byte range" in {
      // Body "0123456789" split across chunks so the slice must span boundaries.
      val media = chunked("video/mp4", List("012", "345", "6789"))
      Get("/media/vid/720p.mp4") ~> addHeader(
        Range.parseFromValueString("bytes=0-4").toOption.get
      ) ~>
        routesFor(Some(media)) ~> check {
          status shouldBe StatusCodes.PartialContent
          header("Content-Range").map(_.value) shouldBe Some("bytes 0-4/10")
          contentType.value shouldBe "video/mp4"
          responseAs[String] shouldBe "01234"
        }
    }

    "serve the tail for an open-ended byte range" in {
      val media = chunked("video/mp4", List("012", "345", "6789"))
      Get("/media/vid/720p.mp4") ~> addHeader(
        Range.parseFromValueString("bytes=5-").toOption.get
      ) ~>
        routesFor(Some(media)) ~> check {
          status shouldBe StatusCodes.PartialContent
          header("Content-Range").map(_.value) shouldBe Some("bytes 5-9/10")
          responseAs[String] shouldBe "56789"
        }
    }

    "return 416 with Content-Range bytes */size for a range starting past the end" in {
      val media = chunked("video/mp4", List("012", "345", "6789")) // size 10
      Get("/media/vid/720p.mp4") ~> addHeader(
        Range.parseFromValueString("bytes=10-20").toOption.get
      ) ~>
        routesFor(Some(media)) ~> check {
          status shouldBe StatusCodes.RangeNotSatisfiable
          header("Content-Range").map(_.value) shouldBe Some("bytes */10")
        }
    }

    "serve the last N bytes for a suffix range" in {
      val media = chunked("video/mp4", List("012", "345", "6789")) // size 10
      Get("/media/vid/720p.mp4") ~> addHeader(
        Range.parseFromValueString("bytes=-4").toOption.get
      ) ~>
        routesFor(Some(media)) ~> check {
          status shouldBe StatusCodes.PartialContent
          header("Content-Range").map(_.value) shouldBe Some("bytes 6-9/10")
          responseAs[String] shouldBe "6789"
        }
    }

    "clamp a closed range whose end exceeds the size" in {
      val media = chunked("video/mp4", List("012", "345", "6789")) // size 10
      Get("/media/vid/720p.mp4") ~> addHeader(
        Range.parseFromValueString("bytes=0-100").toOption.get
      ) ~>
        routesFor(Some(media)) ~> check {
          status shouldBe StatusCodes.PartialContent
          header("Content-Range").map(_.value) shouldBe Some("bytes 0-9/10")
          responseAs[String] shouldBe "0123456789"
        }
    }

    "fall back to 200 full body for a Range on an unknown-size object" in {
      val media = MediaObject("video/mp4", None, Source.single(ByteString("0123456789")))
      Get("/media/vid/720p.mp4") ~> addHeader(
        Range.parseFromValueString("bytes=0-4").toOption.get
      ) ~>
        routesFor(Some(media)) ~> check {
          status shouldBe StatusCodes.OK
          responseAs[String] shouldBe "0123456789"
        }
    }
  }
