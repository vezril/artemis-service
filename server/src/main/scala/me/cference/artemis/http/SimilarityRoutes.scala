package me.cference.artemis.http

import me.cference.artemis.domain.SimilarPost
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.Future

/**
 * The Tier-1 "find similar" surface (similarity-search spec, task 3.1):
 *   - `GET /posts/{id}/similar` — near-duplicates of an existing post
 *   - `GET /similar?phash=` — reverse-image lookup from a provided perceptual hash
 *
 * Both return posts within a Hamming `threshold` (default 10), closest first, limited (default 20,
 * capped 100). The two functions are injected (production: `SimilarityService`) so the routes test
 * with no DB. Path lengths are disjoint from the catalog `posts/{id}` route.
 */
final class SimilarityRoutes(
    similarTo: (String, Int, Int) => Future[Seq[SimilarPost]],
    reverseLookup: (String, Int, Int) => Future[Seq[SimilarPost]]
):

  import SimilarityJson.given
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*

  private val DefaultThreshold: Int = 10
  private val DefaultLimit: Int = 20
  private val MaxLimit: Int = 100

  def routes: Route = concat(similarToRoute, reverseRoute)

  // GET /posts/{id}/similar?threshold=&limit=
  private def similarToRoute: Route =
    (path("posts" / Segment / "similar") & get & parameters(
      "threshold".as[Int].optional,
      "limit".as[Int].optional
    )) { (id, threshold, limit) =>
      onSuccess(similarTo(id, clampThreshold(threshold), clampLimit(limit))) { matches =>
        complete(responseOf(matches))
      }
    }

  // GET /similar?phash=&threshold=&limit=
  private def reverseRoute: Route =
    (path("similar") & get & parameters(
      "phash",
      "threshold".as[Int].optional,
      "limit".as[Int].optional
    )) { (phash, threshold, limit) =>
      onSuccess(reverseLookup(phash, clampThreshold(threshold), clampLimit(limit))) { matches =>
        complete(responseOf(matches))
      }
    }

  private def clampThreshold(t: Option[Int]): Int = math.max(0, t.getOrElse(DefaultThreshold))
  private def clampLimit(l: Option[Int]): Int =
    math.max(0, math.min(l.getOrElse(DefaultLimit), MaxLimit))

  private def responseOf(matches: Seq[SimilarPost]): SimilarPostsResponse =
    SimilarPostsResponse(matches.map(m => SimilarPostItem(m.id, m.distance)).toList)

object SimilarityRoutes:
  def apply(
      similarTo: (String, Int, Int) => Future[Seq[SimilarPost]],
      reverseLookup: (String, Int, Int) => Future[Seq[SimilarPost]]
  ): SimilarityRoutes = new SimilarityRoutes(similarTo, reverseLookup)
