package me.cference.artemis.http

import me.cference.artemis.projection.{FacetEntry, TagSuggestion}
import me.cference.artemis.search.{MetatagCatalog, SearchError, SearchPage}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.Future

/**
 * The DSL read surface (catalog-api "Read endpoints served from projections", tasks 5.1-5.4): `GET
 * /posts` (DSL search + order + keyset cursor), `GET /posts/facets` (tags-in-results grouped by
 * category), and `GET /tags/autocomplete` (context-aware). Every [[SearchError]] maps to `400` with
 * its message — a search failure is never a 500.
 *
 * This is its OWN class (not folded into [[CatalogRoutes]]) so the write/read-by-id surface keeps
 * its exact constructor and spec. The app composes `searchRoutes.routes ~ catalogRoutes.routes` —
 * SEARCH FIRST, so `GET /posts` and `GET /posts/facets` are claimed here before Catalog's `GET
 * /posts/{id}` (`path(Segment)`) could otherwise capture `facets` as an id.
 *
 * The backends are injected as plain functions (production: `SearchService.search` /
 * `SearchService.facets` / `ReadModelRepository.autocompleteTags`) so the routes are fakeable with
 * no DB. Autocomplete's `context=metatag` branch is served purely from [[MetatagCatalog]].
 */
final class SearchRoutes(
    searchFn: (String, Option[String], Option[String], Int) => Future[
      Either[SearchError, SearchPage]
    ],
    facetsFn: String => Future[Either[SearchError, Seq[FacetEntry]]],
    autocompleteTagsFn: (String, Int) => Future[Seq[TagSuggestion]],
    listPoolsFn: (Option[String], Int) => Future[Either[String, PoolListResponse]],
    poolPostsFn: (String, Option[String], Int) => Future[Either[String, SearchResponse]]
):

  import SearchJson.given
  import CatalogJson.given
  import PoolReadJson.given
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*

  /**
   * Default page size when the request omits `limit=`; the executor still clamps to its ceiling.
   */
  private val DefaultPageSize: Int = 40

  /** Default autocomplete result cap. */
  private val AutocompleteLimit: Int = 20

  def routes: Route =
    concat(facetsRoute, postsRoute, autocompleteRoute, poolMembersRoute, poolsListRoute)

  // GET /posts?tags=<DSL>&order=&cursor=&limit=
  private def postsRoute: Route =
    (path("posts") & get & parameters(
      "tags",
      "order".optional,
      "cursor".optional,
      "limit".as[Int].optional
    )) { (tags, order, cursor, limit) =>
      onSuccess(searchFn(tags, order, cursor, limit.getOrElse(DefaultPageSize))) {
        case Right(page) => complete(SearchJson.searchResponse(page.rows, page.nextCursor))
        case Left(err) => complete(badRequest(err))
      }
    }

  // GET /posts/facets?tags=<DSL>
  private def facetsRoute: Route =
    (path("posts" / "facets") & get & parameter("tags")) { tags =>
      onSuccess(facetsFn(tags)) {
        case Right(entries) => complete(SearchJson.facetsResponse(entries))
        case Left(err) => complete(badRequest(err))
      }
    }

  // GET /tags/autocomplete?q=&context=tag|metatag
  private def autocompleteRoute: Route =
    (path("tags" / "autocomplete") & get & parameters("q", "context".optional)) { (q, context) =>
      context match
        case Some("metatag") => complete(MetatagCatalog.suggest(q).toList)
        case _ =>
          onSuccess(autocompleteTagsFn(q, AutocompleteLimit)) { suggestions =>
            complete(suggestions.map(SearchJson.suggestionOf).toList)
          }
    }

  // GET /pools?cursor=&limit= — keyset page of pool cards (name-ordered, visible count + cover).
  private def poolsListRoute: Route =
    (path("pools") & get & parameters("cursor".optional, "limit".as[Int].optional)) {
      (cursor, limit) =>
        onSuccess(listPoolsFn(cursor, limit.getOrElse(PoolReadService.DefaultPageSize))) {
          case Right(resp) => complete(resp)
          case Left(msg) => complete(StatusCodes.BadRequest -> ErrorResponse(msg))
        }
    }

  // GET /pools/{id}/posts?cursor=&limit= — hydrated members in pool order. Claimed here (SearchRoutes
  // composed first) before CatalogRoutes' `GET /pools/{id}` could capture the segment.
  private def poolMembersRoute: Route =
    (path("pools" / Segment / "posts") & get & parameters(
      "cursor".optional,
      "limit".as[Int].optional
    )) { (id, cursor, limit) =>
      onSuccess(poolPostsFn(id, cursor, limit.getOrElse(PoolReadService.DefaultPageSize))) {
        case Right(resp) => complete(resp)
        case Left(msg) => complete(StatusCodes.BadRequest -> ErrorResponse(msg))
      }
    }

  private def badRequest(err: SearchError) =
    StatusCodes.BadRequest -> ErrorResponse(err.message)
