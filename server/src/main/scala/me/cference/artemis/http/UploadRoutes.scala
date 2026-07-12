package me.cference.artemis.http

import me.cference.artemis.ingest.UploadResult
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * The HTTP upload front door (catalog-api "HTTP upload endpoint"). `POST /uploads` streams the raw
 * request body into the upload write path — content-address the object to Apollo, seed a pending
 * post, publish a `ProcessMediaJob` — and answers `201 { postId, status:"pending" }`. Everything
 * downstream (consume loop → projection → search) already runs; this is the missing HTTP seam.
 *
 * The request `Content-Type` carries the media MIME type; the media class (`image`/`video`/…) is
 * derived from it and overridable with `?mediaType=`. The body has no size limit (media files are
 * large; the md5-buffering tradeoff is accepted upstream in `UploadService`). An `UploadService`
 * failure means a downstream dependency (Apollo/Hermes) failed → `502`, not a `500`.
 *
 * `upload` is injected (production: `UploadService.upload`) so the route tests against a fake with
 * no Apollo/Hermes, like the sibling routes.
 */
final class UploadRoutes(
    upload: (Source[ByteString, ?], String, String) => Future[UploadResult]
):

  import CatalogJson.given
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*

  def routes: Route =
    (path("uploads") & post & withoutSizeLimit) {
      extractRequestEntity { entity =>
        parameter("mediaType".optional) { mediaTypeOverride =>
          // Reject an explicitly-empty body up front: uploading nothing would otherwise seed an
          // orphan pending post and enqueue a processing job doomed to fail media decode, wedging
          // the post in `pending`. A clean client error is better than a stuck post.
          if entity.contentLengthOption.contains(0L) then
            complete(StatusCodes.BadRequest -> ErrorResponse("empty upload body"))
          else
            val contentType = entity.contentType.value
            val mediaType =
              mediaTypeOverride.filter(_.nonEmpty).getOrElse(UploadRoutes.mediaClassOf(contentType))
            onComplete(upload(entity.dataBytes, contentType, mediaType)) {
              case Success(result) =>
                complete(StatusCodes.Created -> UploadResponse(result.postId.value, result.status))
              case Failure(ex) =>
                // The only failure `UploadService.upload` surfaces is a downstream put/publish error
                // — an upstream-dependency problem, so a Bad Gateway, not an ambiguous 500.
                complete(StatusCodes.BadGateway -> ErrorResponse(UploadRoutes.messageOf(ex)))
            }
        }
      }
    }

object UploadRoutes:
  def apply(
      upload: (Source[ByteString, ?], String, String) => Future[UploadResult]
  ): UploadRoutes = new UploadRoutes(upload)

  /**
   * Hephaestus's media class from a MIME type: the top-level type — `image/png` → `image`,
   * `video/mp4` → `video`. Defaults to `application` for a typeless/`application/octet-stream`
   * body, which the client can override with `?mediaType=`.
   */
  private[http] def mediaClassOf(contentType: String): String =
    val top = contentType.takeWhile(_ != '/').trim.toLowerCase
    if top.nonEmpty then top else "application"

  private def messageOf(ex: Throwable): String =
    Option(ex.getMessage).getOrElse("upload failed")
