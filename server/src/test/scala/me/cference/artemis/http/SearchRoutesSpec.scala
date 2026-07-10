package me.cference.artemis.http

import me.cference.artemis.projection.{FacetEntry, PostRow, TagSuggestion}
import me.cference.artemis.search.{SearchError, SearchPage}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.Future

/**
 * Route tests for the DSL read surface (task 5.4): `GET /posts`, `GET /posts/facets`, and `GET
 * /tags/autocomplete`. Backed by FAKE service functions (no DB, no cluster) so this asserts the
 * wiring — status codes, JSON shape, context branching, and the 400 on a bad query. The real SQL is
 * proven by `FacetsIT`/`AutocompleteIT`/`SearchQueryIT`.
 */
final class SearchRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  // Don't let the shared runtime config's coordinated-shutdown kill the forked test JVM when the
  // route-test ActorSystem terminates — it races the sbt fork harness (spurious EOFException).
  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  private def row(id: String): PostRow =
    PostRow(
      id = id,
      tags = Seq("1girl", "cat_ears"),
      status = "active",
      score = 42,
      favCount = 3,
      rating = Some("s"),
      width = Some(800),
      height = Some(600),
      duration = None,
      parentId = None,
      duplicateOf = None,
      createdAt = Instant.parse("2026-01-01T00:00:00Z")
    )

  private val page = SearchPage(Seq(row("p1"), row("p2")), Some("cursor-xyz"))

  // Fake backends: a `tags` value of "bad" simulates a rejected query.
  private val searchFn
      : (String, Option[String], Option[String], Int) => Future[Either[SearchError, SearchPage]] =
    (tags, _, _, _) =>
      if tags == "bad" then Future.successful(Left(SearchError.BadQuery("no positive anchor")))
      else Future.successful(Right(page))

  private val facetsFn: String => Future[Either[SearchError, Seq[FacetEntry]]] =
    _ =>
      Future.successful(
        Right(
          Seq(FacetEntry("1girl", 0, 3), FacetEntry("cat_ears", 0, 2), FacetEntry("meta_x", 5, 1))
        )
      )

  private val autocompleteTagsFn: (String, Int) => Future[Seq[TagSuggestion]] =
    (_, _) =>
      Future.successful(
        Seq(
          TagSuggestion("cat_ears", 0, 30, None),
          TagSuggestion("catgirl", 0, 5, Some("cat_girl"))
        )
      )

  private def routes: Route =
    new SearchRoutes(searchFn, facetsFn, autocompleteTagsFn).routes

  "GET /posts" should {

    "return 200 with the matching post summaries and the next cursor" in {
      Get("/posts?tags=1girl%20cat_ears&order=score") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"id\":\"p1\"")
        body should include("\"id\":\"p2\"")
        body should include("\"score\":42")
        body should include("\"rating\":\"s\"")
        body should include("\"nextCursor\":\"cursor-xyz\"")
      }
    }

    "return 400 with the error message for a bad query" in {
      Get("/posts?tags=bad") ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("no positive anchor")
      }
    }
  }

  "GET /posts/facets" should {
    "return 200 with facets grouped by category" in {
      Get("/posts/facets?tags=1girl") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"category\":0")
        body should include("\"category\":5")
        body should include("\"name\":\"1girl\"")
        body should include("\"count\":3")
      }
    }
  }

  "GET /tags/autocomplete" should {

    "return 200 with tag suggestions in the default (tag) context" in {
      Get("/tags/autocomplete?q=cat") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"name\":\"cat_ears\"")
        body should include("\"post_count\":30")
        body should include("\"alias_of\":\"cat_girl\"")
      }
    }

    "return the enum values for a metatag context" in {
      Get("/tags/autocomplete?q=rating:&context=metatag") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"g\"")
        body should include("\"e\"")
        body should not include "post_count"
      }
    }
  }

  "route composition" should {

    "reach the facets handler for /posts/facets even composed with a /posts/{id} route" in {
      // CatalogRoutes serves GET /posts/{id} via `path("posts" / Segment)`, which would capture
      // "facets" as an id if it ran first. Composing search-first must intercept /posts/facets.
      import org.apache.pekko.http.scaladsl.server.Directives.*
      val idRoute: Route = path("posts" / Segment)(id => get(complete(s"post:$id")))
      val composed = routes ~ idRoute
      Get("/posts/facets?tags=1girl") ~> composed ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("\"name\":\"1girl\"") // facet handler, not "post:facets"
      }
    }

    "omit nextCursor when there is no next page" in {
      val noCursor: (String, Option[String], Option[String], Int) => Future[
        Either[SearchError, SearchPage]
      ] =
        (_, _, _, _) => Future.successful(Right(SearchPage(Seq(row("p1")), None)))
      val r = new SearchRoutes(noCursor, facetsFn, autocompleteTagsFn).routes
      Get("/posts?tags=1girl") ~> r ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should not include "nextCursor"
      }
    }
  }
