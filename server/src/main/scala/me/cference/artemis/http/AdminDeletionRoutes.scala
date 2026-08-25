package me.cference.artemis.http

import me.cference.artemis.domain.PostCommand
import me.cference.artemis.domain.PostCommand.{Delete, Restore}
import me.cference.artemis.gc.PurgeService
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

import java.time.Instant
import scala.concurrent.Future
import scala.util.{Failure, Success}

/** `DELETE /posts/{id}` and `POST /posts/{id}/restore` response: the id and resulting status. */
final case class PostStatusResponse(id: String, status: String)

/**
 * `POST /posts/{id}/purge` response: whether the post was purged and how many blobs were removed.
 */
final case class PurgeResponse(id: String, purged: Boolean, blobsDeleted: Int)

object AdminDeletionJson:
  given RootJsonFormat[PostStatusResponse] = jsonFormat2(PostStatusResponse.apply)
  given RootJsonFormat[PurgeResponse] = jsonFormat3(PurgeResponse.apply)

/**
 * The per-post deletion-lifecycle admin surface (admin-deletion spec): soft-delete, restore, and
 * immediate hard-purge on the `/posts/{id}` resource. Soft-delete/restore go straight through the
 * post aggregate via the same `onExecute` seam the catalog routes use (unknown post →
 * `PostNotFound` → 404 via [[HttpErrors]]); purge composes the existing purge-first steps through
 * the injected `PurgeService.purgeNow` seam, so the routes test with fakes — no DB/Apollo.
 *
 * These are new method+path combos (`DELETE /posts/{id}`, `POST /posts/{id}/{restore,purge}`) and
 * do not collide with the catalog routes' GET/PATCH/POST on the same paths.
 */
final class AdminDeletionRoutes(
    postFor: String => RecipientRef[PostEntity.Command],
    purgeNow: String => Future[PurgeService.PurgeOutcome]
)(using system: ActorSystem[?], timeout: Timeout):

  import AdminDeletionJson.given
  import CatalogJson.given // ErrorResponse marshaller
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*

  private def now(): Instant = Instant.now()

  def routes: Route =
    pathPrefix("posts") {
      concat(
        // POST /posts/{id}/restore — restore a soft-deleted post to active.
        (path(Segment / "restore") & post) { id =>
          onExecute(postFor(id), Restore(now())) {
            complete(PostStatusResponse(id, "active"))
          }
        },
        // POST /posts/{id}/purge — immediately hard-purge a soft-deleted post (purge-first, then
        // best-effort blob deletion). A no-longer-Deleted post is an accepted no-op (purged:false).
        (path(Segment / "purge") & post) { id =>
          onComplete(purgeNow(id)) {
            case Success(outcome) =>
              complete(PurgeResponse(id, outcome.purged, outcome.blobsDeleted))
            case Failure(ex) =>
              complete(HttpErrors.statusOf(ex) -> ErrorResponse(HttpErrors.messageOf(ex)))
          }
        },
        // DELETE /posts/{id} — soft-delete: hide from default browse/search, retain blobs.
        (path(Segment) & delete) { id =>
          onExecute(postFor(id), Delete(now())) {
            complete(PostStatusResponse(id, "deleted"))
          }
        }
      )
    }

  /**
   * Send a post command and answer with `onSuccess0`, or the mapped error status via HttpErrors.
   */
  private def onExecute(ref: RecipientRef[PostEntity.Command], cmd: PostCommand)(
      onSuccess0: => Route
  ): Route =
    val ask: Future[Done] = ref.askWithStatus(PostEntity.Execute(cmd, _))
    onComplete(ask) {
      case Success(_) => onSuccess0
      case Failure(ex) =>
        complete(HttpErrors.statusOf(ex) -> ErrorResponse(HttpErrors.messageOf(ex)))
    }

object AdminDeletionRoutes:
  def apply(
      postFor: String => RecipientRef[PostEntity.Command],
      purgeNow: String => Future[PurgeService.PurgeOutcome]
  )(using system: ActorSystem[?], timeout: Timeout): AdminDeletionRoutes =
    new AdminDeletionRoutes(postFor, purgeNow)
