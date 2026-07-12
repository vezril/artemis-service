package me.cference.artemis.http

import me.cference.artemis.projection.RelatedTag
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.Future

/**
 * The related-tags read surface (related-tags spec, task 2.2): `GET /tags/{name}/related` returns
 * the top cosine-ranked tags that co-occur with `{name}`, served from the co-occurrence projection.
 * A tag with no co-occurrences (or no posts) yields an empty list, never an error.
 *
 * The query is injected (production: `ReadModelRepository.relatedTags`) so the route tests with no
 * DB, like the sibling routes. `tags/{name}/related` is 3 path segments, disjoint from
 * `tags/autocomplete` (2 segments), so the two tags-namespace routes never collide.
 */
final class RelatedTagsRoutes(relatedFn: (String, Int) => Future[Seq[RelatedTag]]):

  import RelatedTagsJson.given
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*

  /** Default number of related tags returned when `limit=` is omitted. */
  private val DefaultLimit: Int = 20

  /** Ceiling on `limit`; also floors at 0 so a negative param can't reach SQL and 500. */
  private val MaxLimit: Int = 100

  def routes: Route =
    (path("tags" / Segment / "related") & get & parameter("limit".as[Int].optional)) {
      (name, limit) =>
        val clamped = math.max(0, math.min(limit.getOrElse(DefaultLimit), MaxLimit))
        onSuccess(relatedFn(name, clamped)) { related =>
          complete(
            RelatedTagsResponse(
              related.map(r => RelatedTagItem(r.name, r.cooccurrence, r.cosine)).toList
            )
          )
        }
    }

object RelatedTagsRoutes:
  def apply(relatedFn: (String, Int) => Future[Seq[RelatedTag]]): RelatedTagsRoutes =
    new RelatedTagsRoutes(relatedFn)
