package me.cference.artemis.http

import me.cference.artemis.domain.*
import me.cference.artemis.domain.PostCommand.*
import me.cference.artemis.persistence.{PoolEntity, PostEntity}
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
 * The Catalog write + read-by-id REST/JSON surface (OpenSpec catalog-api, roadmap M6/M7 partial).
 * Writes translate to entity commands and are acknowledged once their event is durably journaled;
 * `GET /posts/{id}` and `GET /pools/{id}` ask the entity directly for read-your-writes freshness.
 *
 * The DSL list/facets/autocomplete reads (`GET /posts`) and the Apollo media gateway are later
 * milestones and are intentionally NOT served here.
 *
 * The class is parameterized over two entity-ref factories so it is production-ready AND testable
 * without a cluster:
 *   - in tests, the factory returns a plain `ActorRef` spawned on the local testkit;
 *   - in production a Main (roadmap M9) supplies `ClusterSharding.entityRefFor` — the factory is
 *     exactly where sharding plugs in. No ClusterSharding/cluster config is wired here yet.
 *
 * `askWithStatus[Done]` and `ask` work uniformly over any `RecipientRef` (plain `ActorRef` or a
 * sharded `EntityRef`).
 *
 * @param postFor
 *   resolve the entity ref for a post id
 * @param poolFor
 *   resolve the entity ref for a pool id
 */
final class CatalogRoutes(
    postFor: String => RecipientRef[PostEntity.Command],
    poolFor: String => RecipientRef[PoolEntity.Command]
)(using system: ActorSystem[?], timeout: Timeout):

  import CatalogJson.given
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*

  private def now(): Instant = Instant.now()

  def routes: Route =
    concat(postRoutes, poolRoutes)

  private def postRoutes: Route =
    pathPrefix("posts") {
      concat(
        // POST /posts — thin create from JSON {id, md5, filetype}. The real byte-upload → Apollo
        // ingest path is the ingest milestone (M8); this only seeds a Pending post.
        (pathEndOrSingleSlash & post & entity(as[CreatePostRequest])) { req =>
          val cmd = CreatePost(PostId.unsafe(req.id), Md5(req.md5), Filetype(req.filetype), now())
          onExecute(postFor(req.id), PostEntity.Execute(cmd, _)) {
            complete(StatusCodes.Created -> CreatePostResponse(req.id, "pending"))
          }
        },
        // PATCH /posts/{id}/tags — set the tag set. The server passes `TagGraph.empty`; the entity
        // canonicalizes (with the empty graph that is just the normalized requested set).
        (path(Segment / "tags") & patch & entity(as[ChangeTagsRequest])) { (id, req) =>
          // Lift each requested name through `Tag.from` so it is normalized (and validated) before
          // the entity canonicalizes it; a malformed tag is a 400 with no command issued.
          val lifted = req.tags.map(Tag.from)
          lifted.collectFirst { case Left(err) => err } match
            case Some(err) =>
              complete(errorStatus(DomainException(err)) -> ErrorResponse(err.message))
            case None =>
              val tags = lifted.collect { case Right(t) => t }.toSet
              executePost(id, ChangeTags(tags, now()))
        },
        // POST /posts/{id}/favorite and DELETE …/favorite.
        path(Segment / "favorite") { id =>
          concat(
            post(executePost(id, Favorite(now()))),
            delete(executePost(id, Unfavorite(now())))
          )
        },
        // POST /posts/{id}/score — apply an integer delta.
        (path(Segment / "score") & post & entity(as[ScoreRequest])) { (id, req) =>
          executePost(id, Score(req.delta, now()))
        },
        // PATCH /posts/{id}/rating — an invalid code is a domain rejection → 400.
        (path(Segment / "rating") & patch & entity(as[SetRatingRequest])) { (id, req) =>
          executePost(id, SetRating(req.rating, now()))
        },
        // GET /posts/{id} — read-your-writes straight from the entity (not the projection, so no
        // lag). A never-created post folds to `Empty` and is a 404.
        (path(Segment) & get) { id =>
          val read: Future[PostState] = postFor(id).ask(PostEntity.Get(_))
          onSuccess(read) {
            // Never-created (Empty) and permanently purged posts are both a read 404.
            case PostState.Empty | _: PostState.Purged =>
              complete(StatusCodes.NotFound -> ErrorResponse("post not found"))
            case state => complete(PostJson.render(id, state))
          }
        }
      )
    }

  /** Send a post command and answer 200 on success (or the mapped error status). */
  private def executePost(id: String, cmd: PostCommand): Route =
    onExecute(postFor(id), PostEntity.Execute(cmd, _))(complete(StatusCodes.OK))

  private def poolRoutes: Route =
    pathPrefix("pools") {
      concat(
        // POST /pools — create from {id, name}.
        (pathEndOrSingleSlash & post & entity(as[CreatePoolRequest])) { req =>
          PoolName.from(req.name) match
            case Left(err) =>
              complete(errorStatus(DomainException(err)) -> ErrorResponse(err.message))
            case Right(name) =>
              val cmd = PoolCommand.CreatePool(PoolId.unsafe(req.id), name, now())
              onExecute(poolFor(req.id), PoolEntity.Execute(cmd, _)) {
                complete(StatusCodes.Created -> CreatePoolResponse(req.id, "active"))
              }
        },
        // POST /pools/{id}/posts — append a post; DELETE /pools/{id}/posts/{postId} — remove one.
        (path(Segment / "posts" / Segment) & delete) { (id, postId) =>
          executePool(id, PoolCommand.RemovePost(PostId.unsafe(postId), now()))
        },
        (path(Segment / "posts") & post & entity(as[AddPostRequest])) { (id, req) =>
          executePool(id, PoolCommand.AddPost(PostId.unsafe(req.postId), now()))
        },
        // PUT /pools/{id}/order — reorder to a permutation of the current membership.
        (path(Segment / "order") & put & entity(as[ReorderRequest])) { (id, req) =>
          executePool(id, PoolCommand.Reorder(req.order.map(PostId.unsafe).toVector, now()))
        },
        // GET /pools/{id} — read-your-writes from the entity; a never-created pool is a 404.
        (path(Segment) & get) { id =>
          val read: Future[PoolState] = poolFor(id).ask(PoolEntity.Get(_))
          onSuccess(read) {
            // Empty (never created) and Deleted (tombstone) are both a read 404.
            case PoolState.Empty | _: PoolState.Deleted =>
              complete(StatusCodes.NotFound -> ErrorResponse("pool not found"))
            case state => complete(PoolJson.render(id, state))
          }
        },
        // PATCH /pools/{id} — rename; DELETE /pools/{id} — delete.
        (path(Segment) & patch & entity(as[RenamePoolRequest])) { (id, req) =>
          PoolName.from(req.name) match
            case Left(err) =>
              complete(errorStatus(DomainException(err)) -> ErrorResponse(err.message))
            case Right(name) => executePool(id, PoolCommand.RenamePool(name, now()))
        },
        (path(Segment) & delete) { id =>
          executePool(id, PoolCommand.DeletePool(now()))
        }
      )
    }

  /** Send a pool command and answer 200 on success (or the mapped error status). */
  private def executePool(id: String, cmd: PoolCommand): Route =
    onExecute(poolFor(id), PoolEntity.Execute(cmd, _))(complete(StatusCodes.OK))

  /**
   * Send an `Execute` write to an entity and translate the `StatusReply` into either the given
   * success `Route` or a mapped error status. The `askWithStatus[Done]` completing IS the durable
   * ack that the event was journaled.
   */
  private def onExecute[C](
      ref: RecipientRef[C],
      makeCmd: org.apache.pekko.actor.typed.ActorRef[
        org.apache.pekko.pattern.StatusReply[Done]
      ] => C
  )(onSuccess0: => Route): Route =
    val ask: Future[Done] = ref.askWithStatus(makeCmd)
    onComplete(ask) {
      case Success(_) => onSuccess0
      case Failure(ex) => complete(errorStatus(ex) -> ErrorResponse(errorMessage(ex)))
    }

  /**
   * Domain failure → HTTP status, via the shared [[HttpErrors]] mapping (single exhaustive match).
   */
  private def errorStatus(ex: Throwable): StatusCode = HttpErrors.statusOf(ex)

  private def errorMessage(ex: Throwable): String = HttpErrors.messageOf(ex)

object CatalogRoutes:
  def apply(
      postFor: String => RecipientRef[PostEntity.Command],
      poolFor: String => RecipientRef[PoolEntity.Command]
  )(using system: ActorSystem[?], timeout: Timeout): CatalogRoutes =
    new CatalogRoutes(postFor, poolFor)
