package me.cference.artemis.http

import me.cference.artemis.reprocess.ReprocessKind
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

import scala.concurrent.Future
import scala.util.{Failure, Success}

/** `POST /reprocess` body: a selection (`stale`, `id:<x>`, or a search DSL query) and a kind. */
final case class ReprocessRequest(select: String, kind: String)

/** `POST /reprocess` response: how many reprocess jobs were enqueued. */
final case class ReprocessResponse(enqueued: Int)

object ReprocessJson:
  given RootJsonFormat[ReprocessRequest] = jsonFormat2(ReprocessRequest.apply)
  given RootJsonFormat[ReprocessResponse] = jsonFormat1(ReprocessResponse.apply)

/**
 * The manual reprocess trigger (reprocess-orchestration spec, task 5.1): `POST /reprocess` with
 * `{select, kind}` resolves the selection and enqueues one backfill job per matching post,
 * answering `{enqueued}`. An unknown `kind` is a 400; a bad DSL selection surfaces as a 400 via the
 * injected function's failed Future. The reprocess is injected (production:
 * `ReprocessService.reprocess`) so the route tests with no DB/Hermes.
 */
final class ReprocessRoutes(reprocess: (String, ReprocessKind) => Future[Int]):

  import ReprocessJson.given
  import CatalogJson.given // ErrorResponse marshaller
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*

  def routes: Route =
    (path("reprocess") & post & entity(as[ReprocessRequest])) { req =>
      ReprocessKind.from(req.kind) match
        case None =>
          complete(
            StatusCodes.BadRequest ->
              ErrorResponse(s"unknown kind '${req.kind}' (expected derivatives | metadata | tags)")
          )
        case Some(kind) =>
          onComplete(reprocess(req.select, kind)) {
            case Success(n) => complete(ReprocessResponse(n))
            case Failure(ex) =>
              complete(StatusCodes.BadRequest -> ErrorResponse(messageOf(ex)))
          }
    }

  private def messageOf(ex: Throwable): String =
    Option(ex.getMessage).getOrElse("reprocess failed")

object ReprocessRoutes:
  def apply(reprocess: (String, ReprocessKind) => Future[Int]): ReprocessRoutes =
    new ReprocessRoutes(reprocess)
