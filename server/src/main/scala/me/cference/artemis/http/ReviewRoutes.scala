package me.cference.artemis.http

import me.cference.artemis.domain.PostCommand.{AcceptSuggestions, RejectSuggestions}
import me.cference.artemis.domain.{DomainException, Tag}
import me.cference.artemis.persistence.PostEntity
import me.cference.artemis.projection.ReviewItem
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
 * The auto-tag review surface (auto-tagging spec, task 5.1):
 *   - `GET /review` — the backlog of posts awaiting review, each with its pending suggestions
 *     (oldest first, `limit`-capped), served from the read model.
 *   - `POST /posts/{id}/review` — resolve one post: `{"accept":[tags]}` applies the chosen tags
 *     (via a normal tag edit, so they flow through canonicalization + history) and clears review;
 *     an empty/absent `accept` rejects all and clears review without applying anything.
 *
 * The queue read is an injected function (production: `ReadModelRepository.reviewQueue`) so the
 * route tests with no DB; the accept/reject issues commands to the sharded post entity via
 * `postFor`, the same seam the catalog routes use. `posts/{id}/review` is a 3-segment path disjoint
 * from the catalog's `posts/{id}` sub-routes.
 */
final class ReviewRoutes(
    reviewQueue: Int => Future[Seq[ReviewItem]],
    postFor: String => RecipientRef[PostEntity.Command]
)(using system: ActorSystem[?], timeout: Timeout):

  import ReviewJson.given
  import CatalogJson.given // ErrorResponse marshaller
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*

  private val DefaultLimit: Int = 50
  private val MaxLimit: Int = 200

  def routes: Route = concat(queueRoute, resolveRoute)

  // GET /review?limit=
  private def queueRoute: Route =
    (path("review") & get & parameter("limit".as[Int].optional)) { limit =>
      val clamped = math.max(0, math.min(limit.getOrElse(DefaultLimit), MaxLimit))
      onSuccess(reviewQueue(clamped)) { items =>
        complete(ReviewQueueResponse(items.map(render).toList))
      }
    }

  // POST /posts/{id}/review  {"accept":[tags]}
  private def resolveRoute: Route =
    (path("posts" / Segment / "review") & post & entity(as[ReviewRequest])) { (id, req) =>
      val requested = req.accept.getOrElse(Nil)
      // Lift each chosen name through `Tag.from` so a malformed tag is a 400 with no command.
      val lifted = requested.map(Tag.from)
      lifted.collectFirst { case Left(err) => err } match
        case Some(err) =>
          complete(HttpErrors.statusOf(DomainException(err)) -> ErrorResponse(err.message))
        case None =>
          val accepted = lifted.collect { case Right(t) => t }.toSet
          val cmd =
            if accepted.isEmpty then RejectSuggestions(Instant.now())
            else AcceptSuggestions(accepted, Instant.now())
          onExecute(postFor(id), PostEntity.Execute(cmd, _))(complete(StatusCodes.OK))
    }

  private def render(item: ReviewItem): ReviewPostItem =
    ReviewPostItem(
      item.postId,
      item.suggestions.map(s => SuggestionItem(s.tag, s.confidence, s.source)).toList
    )

  /** Send an entity write and answer 200 on success, or the mapped domain-error status. */
  private def onExecute(
      ref: RecipientRef[PostEntity.Command],
      makeCmd: org.apache.pekko.actor.typed.ActorRef[
        org.apache.pekko.pattern.StatusReply[Done]
      ] => PostEntity.Command
  )(onSuccess0: => Route): Route =
    onComplete(ref.askWithStatus(makeCmd): Future[Done]) {
      case Success(_) => onSuccess0
      case Failure(ex) =>
        complete(errorStatus(ex) -> ErrorResponse(HttpErrors.messageOf(ex)))
    }

  private def errorStatus(ex: Throwable): StatusCode = HttpErrors.statusOf(ex)

object ReviewRoutes:
  def apply(
      reviewQueue: Int => Future[Seq[ReviewItem]],
      postFor: String => RecipientRef[PostEntity.Command]
  )(using system: ActorSystem[?], timeout: Timeout): ReviewRoutes =
    new ReviewRoutes(reviewQueue, postFor)
