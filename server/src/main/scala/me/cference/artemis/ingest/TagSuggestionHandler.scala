package me.cference.artemis.ingest

import codex.messages.v1.TagSuggestions
import me.cference.artemis.domain.PostCommand.RecordSuggestions
import me.cference.artemis.domain.PostState.{Active, Pending}
import me.cference.artemis.domain.SuggestionMerge.RawSuggestion
import me.cference.artemis.domain.{PostState, SuggestionMerge, TagGraph}
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.Future
import scala.util.control.NonFatal

/**
 * The auto-tag consume path (auto-tagging spec, task 3.2): drive a post from Argus's
 * `TagSuggestions`. Raw suggestions (from two vocabularies) are alias-merged into Artemis's
 * canonical set via [[SuggestionMerge]] against the current tag graph, then recorded on the post
 * (`RecordSuggestions`), which flags it for review.
 *
 * Redelivery handling: `RecordSuggestions` replaces the pending set (idempotent while the post is
 * still awaiting review), so a redelivery of the same message re-records an equivalent set. If the
 * post isn't active yet — the tiny window before its `MediaProcessed` folds — the command is
 * rejected; we distinguish that TRANSIENT case (still `Pending`, leave un-acked for redelivery)
 * from a TERMINAL one (the post was deleted/failed/absent between the TagJob and the suggestions,
 * which would otherwise poison-loop forever since the M9 DLQ is deferred) and ack-drop the latter.
 *
 * Known limitation: a `TagSuggestions` carries no message-id/version, so a stale redelivery that
 * arrives AFTER a human already reviewed is indistinguishable from a legitimate re-tag and will
 * re-flag the post for review. The impact is bounded and safe (suggestions are never auto-applied —
 * worst case is a redundant human re-review); a durable fix needs a dedup token in the contract.
 */
final class TagSuggestionHandler(
    postFor: String => RecipientRef[PostEntity.Command],
    tagGraph: () => TagGraph
)(using system: ActorSystem[?], timeout: Timeout):

  private given scala.concurrent.ExecutionContext = system.executionContext
  private val log = LoggerFactory.getLogger(getClass)

  def onSuggestions(m: TagSuggestions): Future[Unit] =
    val merged = SuggestionMerge.merge(rawOf(m), tagGraph())
    if merged.isEmpty then
      // Nothing canonical to review (Argus returned none, or all were malformed) — accept + drop.
      log.debug("no canonical suggestions for post {}; nothing to record", m.postId)
      Future.unit
    else
      postFor(m.postId)
        .askWithStatus[org.apache.pekko.Done](
          PostEntity.Execute(RecordSuggestions(merged, Instant.now()), _)
        )
        .map(_ => ())
        .recoverWith { case NonFatal(cause) => onRecordFailure(m.postId, cause) }

  /**
   * A `RecordSuggestions` failure means the post is not active. Re-read its state: a `Pending` (or,
   * defensively, still-`Active`) post is a transient miss — re-fail so the message is redelivered;
   * any terminal/absent state is permanent, so ack-drop rather than poison-loop forever.
   */
  private def onRecordFailure(postId: String, cause: Throwable): Future[Unit] =
    postFor(postId)
      .ask[PostState](PostEntity.Get(_))
      .flatMap {
        case _: Pending | _: Active => Future.failed(cause) // transient — leave un-acked
        case terminal =>
          log.warn(
            "dropping tag suggestions for post {} in terminal state {} (not retryable)",
            postId,
            terminal.getClass.getSimpleName
          )
          Future.unit
      }

  private def rawOf(m: TagSuggestions): Seq[RawSuggestion] =
    m.suggestions.map(s => RawSuggestion(s.tag, s.confidence, s.source))
