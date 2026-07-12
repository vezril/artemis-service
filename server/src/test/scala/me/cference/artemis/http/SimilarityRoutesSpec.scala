package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.domain.SimilarPost
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Future

/**
 * Route tests for `GET /posts/{id}/similar` and `GET /similar?phash=` (similarity-search spec, task
 * 3.1). Backed by FAKE functions (no DB), so this asserts the wiring — status, JSON shape, the
 * threshold/limit params, the reverse-lookup phash param, and the empty case. The real Hamming
 * search is proven by `SimilaritySearchSpec` (pure) and `SimilaritySearchIT` (DB).
 */
final class SimilarityRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  // Records (id-or-phash, threshold, limit) of the last call.
  private val lastSimilar = new AtomicReference[Option[(String, Int, Int)]](None)
  private val lastReverse = new AtomicReference[Option[(String, Int, Int)]](None)

  private val similarTo: (String, Int, Int) => Future[Seq[SimilarPost]] =
    (id, t, l) =>
      lastSimilar.set(Some((id, t, l)))
      if id == "lonely" then Future.successful(Seq.empty)
      else Future.successful(Seq(SimilarPost("near1", 2), SimilarPost("near2", 6)))

  private val reverseLookup: (String, Int, Int) => Future[Seq[SimilarPost]] =
    (phash, t, l) =>
      lastReverse.set(Some((phash, t, l)))
      Future.successful(Seq(SimilarPost("match", 3)))

  private def routes: Route = new SimilarityRoutes(similarTo, reverseLookup).routes

  "GET /posts/{id}/similar" should {

    "return 200 with near-duplicates, closest first" in {
      Get("/posts/A/similar") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val b = responseAs[String]
        b should include("\"id\":\"near1\"")
        b should include("\"distance\":2")
        b should include("\"id\":\"near2\"")
      }
      lastSimilar.get() shouldBe Some(("A", 10, 20)) // defaults
    }

    "honor threshold and limit params (limit capped at 100)" in {
      Get("/posts/A/similar?threshold=5&limit=500") ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      lastSimilar.get() shouldBe Some(("A", 5, 100))
    }

    "return 200 with an empty list when there are no near matches" in {
      Get("/posts/lonely/similar") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"similar\":[]")
      }
    }
  }

  "GET /similar?phash=" should {
    "run the reverse-image lookup with the provided phash" in {
      Get("/similar?phash=00ff00ff&threshold=8") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"id\":\"match\"")
      }
      lastReverse.get() shouldBe Some(("00ff00ff", 8, 20))
    }
  }
