package me.cference.artemis.projection

import me.cference.artemis.domain.PostEvent.*
import me.cference.artemis.domain.{Derivative, PostEvent}
import org.apache.pekko.Done
import org.apache.pekko.persistence.query.typed.EventEnvelope
import org.apache.pekko.projection.r2dbc.scaladsl.{R2dbcHandler, R2dbcSession}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Folds post journal events into the read model. Every write is idempotent, so at-least-once
 * redelivery is safe: upserts keyed by id, absolute `score` (the `Scored` event carries the total)
 * and `fav_count`, read-current-delta `post_count` maintenance in [[ReadModelRepository.setTags]],
 * and status-guarded delete/restore count adjustments. The post id comes from the persistence id
 * (`post|<id>`); it delegates to the repo's own connection rather than the projection `session`,
 * observably equivalent to exactly-once given the idempotent writes.
 */
final class PostProjectionHandler(repo: ReadModelRepository)(using ec: ExecutionContext)
    extends R2dbcHandler[EventEnvelope[PostEvent]]:

  def process(session: R2dbcSession, envelope: EventEnvelope[PostEvent]): Future[Done] =
    val id = envelope.persistenceId.substring(envelope.persistenceId.indexOf('|') + 1)
    val applied = envelope.event match
      case e: PostCreated =>
        repo.upsertPostCreated(id, e.md5.value, e.filetype.value, "pending", e.at)
      case e: MediaProcessed =>
        repo.applyMediaProcessed(
          id,
          e.dimensions.width,
          e.dimensions.height,
          e.dimensions.duration,
          e.phash.value,
          derivativesJson(e.derivatives),
          "active"
        )
      case _: ProcessingFailed => repo.setStatus(id, "failed")
      case e: TagsChanged => repo.setTags(id, e.tags.map(_.value).toSeq)
      case e: RatingChanged => repo.setRating(id, e.rating.code)
      case e: ParentSet => repo.setParent(id, e.parent.value)
      case e: SourceChanged => repo.setSource(id, e.source)
      case e: Scored => repo.setScore(id, e.score)
      case _: Favorited => repo.setFavorited(id, true)
      case _: Unfavorited => repo.setFavorited(id, false)
      case e: PossibleDuplicateFlagged => repo.setDuplicateOf(id, e.matchedPostId.value)
      case e: SuggestionsRecorded => repo.setSuggestions(id, SuggestionJson.encode(e.suggestions))
      case _: SuggestionsReviewed => repo.clearReview(id)
      case _: PostDeleted => repo.deletePost(id)
      case _: PostRestored => repo.restorePost(id)
    applied.map(_ => Done)

  /**
   * Serialize derivatives to a JSON array of `{"kind":...,"ref":...}` for the `derivatives` jsonb.
   */
  private def derivativesJson(derivatives: Vector[Derivative]): String =
    derivatives
      .map(d => s"""{"kind":${jsonString(d.kind)},"ref":${jsonString(d.ref)}}""")
      .mkString("[", ",", "]")

  private def jsonString(s: String): String =
    val escaped = s
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    s"\"$escaped\""
