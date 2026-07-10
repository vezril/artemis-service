package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.projection.RelatedTag
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

/**
 * Route test for `GET /tags/{name}/related` (related-tags spec, task 2.2). Backed by a FAKE related
 * function (no DB), so this asserts the wiring — status, JSON shape, the `limit` param, and the
 * empty case. The real cosine query is proven by `RelatedTagsIT`.
 */
final class RelatedTagsRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  private val lastCall = new AtomicReference[Option[(String, Int)]](None)

  private val relatedFn: (String, Int) => Future[Seq[RelatedTag]] =
    (name, limit) =>
      lastCall.set(Some((name, limit)))
      if name == "lonely" then Future.successful(Seq.empty)
      else
        Future.successful(
          Seq(RelatedTag("cat_girl", 3, 1.0), RelatedTag("1girl", 3, 0.5477))
        )

  private def routes: Route = new RelatedTagsRoutes(relatedFn).routes

  "GET /tags/{name}/related" should {

    "return 200 with cosine-ranked related tags" in {
      Get("/tags/cat_ears/related") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"name\":\"cat_girl\"")
        body should include("\"cooccurrence\":3")
        body should include("\"cosine\":1.0")
        body should include("\"name\":\"1girl\"")
      }
      lastCall.get().map(_._1) shouldBe Some("cat_ears")
      lastCall.get().map(_._2) shouldBe Some(20) // default limit
    }

    "honor the limit query parameter" in {
      Get("/tags/cat_ears/related?limit=5") ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      lastCall.get().map(_._2) shouldBe Some(5)
    }

    "return 200 with an empty list for a tag with no related tags" in {
      Get("/tags/lonely/related") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"related\":[]")
      }
    }
  }
