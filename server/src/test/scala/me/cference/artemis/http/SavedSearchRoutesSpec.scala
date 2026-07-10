package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.persistence.SavedSearchesEntity
import me.cference.artemis.projection.PostRow
import me.cference.artemis.search.{SearchError, SearchPage}
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.RecipientRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * Route tests for the saved-searches API (saved-searches spec, tasks 2.1/2.2). Spawns a REAL
 * `SavedSearchesEntity` on the in-memory persistence-testkit journal (no Docker), so save/rename/
 * remove/list round-trip through the aggregate, and asserts that RUNNING a saved search resolves to
 * its stored DSL query and calls the (fake) search function with exactly that query — "same results
 * as typing it". The real DSL execution is proven by `SearchQueryIT`.
 */
final class SavedSearchRoutesSpec
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
      .withFallback(PersistenceTestKitPlugin.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
      .withFallback(ConfigFactory.load())

  private val testKit: ActorTestKit = ActorTestKit(system.toTyped)
  private given Timeout = Timeout(3.seconds)
  private given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system

  override def afterAll(): Unit = testKit.shutdownTestKit()

  private val ids = new AtomicInteger(0)

  /**
   * A fresh, isolated saved-searches entity + routes per test; the fake search fn records its
   * query.
   */
  private def fixture(): (Route, AtomicReference[Option[String]]) =
    val ref: RecipientRef[SavedSearchesEntity.Command] =
      testKit.spawn(SavedSearchesEntity(s"test-${ids.incrementAndGet()}"))
    val lastQuery = new AtomicReference[Option[String]](None)
    val searchFn: (String, Option[String], Option[String], Int) => Future[
      Either[SearchError, SearchPage]
    ] =
      (q, _, _, _) =>
        lastQuery.set(Some(q))
        Future.successful(Right(SearchPage(Seq(row("p1")), Some("next-cursor"))))
    val routes = new SavedSearchRoutes(() => ref, searchFn).routes
    (routes, lastQuery)

  private def row(id: String): PostRow =
    PostRow(
      id,
      Seq("cat_ears"),
      "active",
      0,
      0,
      None,
      None,
      None,
      None,
      None,
      None,
      Instant.parse("2026-01-01T00:00:00Z")
    )

  private def json(body: String) = HttpEntity(ContentTypes.`application/json`, body)

  "POST /saved-searches" should {
    "save a named query and list it" in {
      val (routes, _) = fixture()
      Post("/saved-searches", json("""{"name":"cat videos","query":"cat_ears is:video"}""")) ~>
        routes ~> check {
          status shouldBe StatusCodes.Created
        }
      Get("/saved-searches") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val b = responseAs[String]
        b should include("\"name\":\"cat videos\"")
        b should include("\"query\":\"cat_ears is:video\"")
      }
    }

    "reject an empty name with 400" in {
      val (routes, _) = fixture()
      Post("/saved-searches", json("""{"name":"  ","query":"x"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "GET /saved-searches/{name}/results" should {
    "run the stored query — same query the DSL would receive if typed" in {
      val (routes, lastQuery) = fixture()
      Post("/saved-searches", json("""{"name":"cat videos","query":"cat_ears is:video"}""")) ~>
        routes ~> check(status shouldBe StatusCodes.Created)

      Get("/saved-searches/cat%20videos/results") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val b = responseAs[String]
        b should include("\"id\":\"p1\"")
        b should include("\"nextCursor\":\"next-cursor\"")
      }
      // The run resolved to the stored query and handed it to the search function verbatim.
      lastQuery.get() shouldBe Some("cat_ears is:video")
    }

    "return 404 for a name that isn't saved" in {
      val (routes, lastQuery) = fixture()
      Get("/saved-searches/ghost/results") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      lastQuery.get() shouldBe None // search was never run
    }
  }

  "PATCH /saved-searches/{name}" should {
    "rename a saved search" in {
      val (routes, _) = fixture()
      Post("/saved-searches", json("""{"name":"old","query":"q"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Patch("/saved-searches/old", json("""{"name":"new"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Get("/saved-searches") ~> routes ~> check {
        val b = responseAs[String]
        b should include("\"name\":\"new\"")
        b should not include "\"name\":\"old\""
      }
    }

    "reject renaming onto an existing name with 409" in {
      val (routes, _) = fixture()
      Post("/saved-searches", json("""{"name":"a","query":"qa"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Post("/saved-searches", json("""{"name":"b","query":"qb"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Patch("/saved-searches/a", json("""{"name":"b"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }
  }

  "DELETE /saved-searches/{name}" should {
    "remove a saved search" in {
      val (routes, _) = fixture()
      Post("/saved-searches", json("""{"name":"temp","query":"q"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Delete("/saved-searches/temp") ~> routes ~> check(status shouldBe StatusCodes.OK)
      Get("/saved-searches") ~> routes ~> check {
        responseAs[String] should not include "\"name\":\"temp\""
      }
    }
  }
