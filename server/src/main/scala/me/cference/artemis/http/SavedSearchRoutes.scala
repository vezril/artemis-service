package me.cference.artemis.http

import me.cference.artemis.domain.SavedSearchesCommand.{RemoveSearch, RenameSearch, SaveSearch}
import me.cference.artemis.domain.{SavedSearchesCommand, SavedSearchesState, SearchName}
import me.cference.artemis.persistence.SavedSearchesEntity
import me.cference.artemis.search.{SearchError, SearchPage}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import java.time.Instant
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * The saved-searches surface (saved-searches spec, task 2.2): `GET /saved-searches` (list), `POST
 * /saved-searches` (save), `PATCH /saved-searches/{name}` (rename), `DELETE /saved-searches/{name}`
 * (remove), and `GET /saved-searches/{name}/results` (run — resolve the stored query and execute
 * the normal DSL search, returning the same shape as `GET /posts`).
 *
 * Reads and writes the single saved-searches entity (single-user = one list) — the list comes
 * straight from the entity for read-your-writes freshness, no separate read table. The entity-ref
 * factory and the search function are injected so the routes test with no cluster/DB, mirroring the
 * sibling routes.
 */
final class SavedSearchRoutes(
    savedSearchesRef: () => RecipientRef[SavedSearchesEntity.Command],
    searchFn: (String, Option[String], Option[String], Int) => Future[
      Either[SearchError, SearchPage]
    ]
)(using system: ActorSystem[?], timeout: Timeout):

  import SavedSearchJson.given
  import CatalogJson.given
  import SearchJson.given
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
  import system.executionContext

  private val DefaultPageSize: Int = 40

  def routes: Route =
    // Run (3 segments) and the base list/create (1 segment) and the {name} routes (2 segments) are
    // disjoint by path length, but list run first for clarity.
    concat(runRoute, listRoute, createRoute, renameRoute, removeRoute)

  // GET /saved-searches
  private def listRoute: Route =
    (path("saved-searches") & get) {
      onSuccess(listSearches())(items => complete(SavedSearchListResponse(items)))
    }

  // POST /saved-searches  { name, query }
  private def createRoute: Route =
    (path("saved-searches") & post & entity(as[SaveSearchRequest])) { req =>
      SearchName.from(req.name) match
        case Left(err) => complete(StatusCodes.BadRequest -> ErrorResponse(err.message))
        case Right(name) =>
          onExecute(SaveSearch(name, req.query, now())) {
            complete(StatusCodes.Created -> SavedSearchItem(name.value, req.query))
          }
    }

  // PATCH /saved-searches/{name}  { name: <new name> }
  private def renameRoute: Route =
    (path("saved-searches" / Segment) & patch & entity(as[RenameSearchRequest])) { (from, req) =>
      (SearchName.from(from), SearchName.from(req.name)) match
        case (Right(f), Right(t)) => onExecute(RenameSearch(f, t, now()))(complete(StatusCodes.OK))
        case (Left(err), _) => complete(StatusCodes.BadRequest -> ErrorResponse(err.message))
        case (_, Left(err)) => complete(StatusCodes.BadRequest -> ErrorResponse(err.message))
    }

  // DELETE /saved-searches/{name}
  private def removeRoute: Route =
    (path("saved-searches" / Segment) & delete) { name =>
      SearchName.from(name) match
        case Left(err) => complete(StatusCodes.BadRequest -> ErrorResponse(err.message))
        case Right(n) => onExecute(RemoveSearch(n, now()))(complete(StatusCodes.OK))
    }

  // GET /saved-searches/{name}/results?order=&cursor=&limit=
  private def runRoute: Route =
    (path("saved-searches" / Segment / "results") & get & parameters(
      "order".optional,
      "cursor".optional,
      "limit".as[Int].optional
    )) { (name, order, cursor, limit) =>
      onSuccess(resolveQuery(name)) {
        case None =>
          complete(StatusCodes.NotFound -> ErrorResponse("saved search does not exist"))
        case Some(query) =>
          onSuccess(searchFn(query, order, cursor, limit.getOrElse(DefaultPageSize))) {
            case Right(page) => complete(SearchJson.searchResponse(page.rows, page.nextCursor))
            case Left(err) => complete(StatusCodes.BadRequest -> ErrorResponse(err.message))
          }
      }
    }

  // --- helpers -----------------------------------------------------------

  private def listSearches(): Future[List[SavedSearchItem]] =
    savedSearchesRef()
      .ask[SavedSearchesState](SavedSearchesEntity.Get(_))
      .map(_.searches.map(s => SavedSearchItem(s.name.value, s.query)).toList)

  private def resolveQuery(name: String): Future[Option[String]] =
    savedSearchesRef()
      .ask[SavedSearchesState](SavedSearchesEntity.Get(_))
      .map(_.searches.find(_.name.value == name).map(_.query))

  /** Send a command to the entity; answer the success route or the mapped error status. */
  private def onExecute(command: SavedSearchesCommand)(onSuccess0: => Route): Route =
    val ask: Future[Done] =
      savedSearchesRef().askWithStatus(SavedSearchesEntity.Execute(command, _))
    onComplete(ask) {
      case Success(_) => onSuccess0
      case Failure(ex) => complete(errorStatus(ex) -> ErrorResponse(HttpErrors.messageOf(ex)))
    }

  private def errorStatus(ex: Throwable): StatusCode = HttpErrors.statusOf(ex)

  private def now(): Instant = Instant.now()

object SavedSearchRoutes:
  def apply(
      savedSearchesRef: () => RecipientRef[SavedSearchesEntity.Command],
      searchFn: (String, Option[String], Option[String], Int) => Future[
        Either[SearchError, SearchPage]
      ]
  )(using system: ActorSystem[?], timeout: Timeout): SavedSearchRoutes =
    new SavedSearchRoutes(savedSearchesRef, searchFn)
