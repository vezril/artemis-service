package me.cference.artemis.hermes

import codex.messages.v1.{MediaFailed, MediaProcessed, TagSuggestions}
import me.cference.artemis.ingest.MediaResultHandler
import me.cference.artemis.messages.MediaMessages
import me.cference.artemis.tracing.{CorrelationId, MdcPropagatingExecutionContext}
import me.cference.hermesmq.grpc.{AckRequest, PullRequest, PulledMessage}
import org.slf4j.{LoggerFactory, MDC}
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * The concrete consume-spine adapter (OpenSpec task 4.2): one `pollOnce` pulls a batch from each
 * result subscription, decodes the canonical-JSON payloads, drives [[MediaResultHandler]], and acks
 * only the messages that were successfully handled. The continuous poll-loop / scheduler (and the
 * streaming `consume` RPC upgrade) are deferred to the M9 cross-cutting milestone — exposing
 * `pollOnce` keeps the transport a unit-testable step.
 *
 * At-least-once discipline (design D-hermes):
 *   - A poison message (payload that will not decode) is logged and left UN-acked, so Hermes
 *     redelivers or dead-letters it rather than the consumer silently dropping it.
 *   - A well-formed message whose handler Future fails is likewise left UN-acked, so redelivery
 *     retries it. Only after the handler succeeds is the message acked.
 *   - `MediaResultHandler` itself is idempotent per `jobId`, so a redelivery after a crash between
 *     handling and ack never double-applies.
 */
final class HermesMediaResultConsumer(
    client: HermesClient,
    processedSub: String,
    failedSub: String,
    handler: MediaResultHandler,
    maxMessages: Int = 100,
    tagSuggestionsSub: String = "",
    tagHandler: TagSuggestions => Future[Unit] = _ => Future.unit
)(using ec: ExecutionContext):

  // The per-message handle chain runs on an MDC-propagating EC so the adopted `correlationId` (set
  // in the MDC below) survives the async decode → handle → ack hops (request-tracing). A plain val
  // (not a given) so it doesn't clash with the injected `ec` the rest of the consumer uses.
  private val tracingEc: ExecutionContext = MdcPropagatingExecutionContext(ec)

  private val log = LoggerFactory.getLogger(getClass)

  /**
   * Pull + handle + ack one batch from each subscription; returns the number of messages acked. The
   * tag-suggestions subscription (Argus → auto-tagging) rides the same at-least-once discipline and
   * is only drained when configured (`tagSuggestionsSub` non-empty).
   */
  def pollOnce(): Future[Int] =
    for
      processed <- drain[MediaProcessed](processedSub, handler.onProcessed)
      failed <- drain[MediaFailed](failedSub, handler.onFailed)
      tags <-
        if tagSuggestionsSub.isEmpty then Future.successful(0)
        else drain[TagSuggestions](tagSuggestionsSub, tagHandler)
    yield processed + failed + tags

  /**
   * Pull up to `maxMessages` from `subscription`, decode each into `A`, run `handle`, and ack the
   * ackIds whose handler succeeded. Returns the count acked. Decode failures and handler failures
   * are logged and their messages left un-acked for redelivery.
   */
  private def drain[A <: GeneratedMessage](subscription: String, handle: A => Future[Unit])(using
      companion: GeneratedMessageCompanion[A]
  ): Future[Int] =
    client.pull(PullRequest(subscriptionId = subscription, max = maxMessages)).flatMap { response =>
      Future
        .sequence(response.messages.map(handleOne(subscription, _, handle)))
        .flatMap { outcomes =>
          val ackIds = outcomes.flatten
          if ackIds.isEmpty then Future.successful(0)
          else
            client
              .ack(AckRequest(subscriptionId = subscription, ackIds = ackIds))
              .map(_ => ackIds.size)
        }
    }

  /** Handle one pulled message; yields its ackId iff it decoded and its handler succeeded. */
  private def handleOne[A <: GeneratedMessage](
      subscription: String,
      pulled: PulledMessage,
      handle: A => Future[Unit]
  )(using companion: GeneratedMessageCompanion[A]): Future[Option[String]] =
    pulled.message match
      case None =>
        log.warn(
          "pulled message {} from {} has no payload; leaving un-acked",
          pulled.ackId,
          subscription
        )
        Future.successful(None)
      case Some(message) =>
        MediaMessages.fromJsonEither[A](message.payload.toStringUtf8) match
          case Left(cause) =>
            log.warn(
              "poison message {} from {} failed to decode ({}); leaving un-acked for redelivery/DLQ",
              pulled.ackId,
              subscription,
              cause.getMessage
            )
            Future.successful(None)
          case Right(decoded) =>
            // Adopt the delivery's correlation id (mint if empty) into the MDC so the handling of
            // this result — including the async activation/apply work — logs under the SAME id as
            // the ingest that caused it (request-tracing). Set on this thread + `try/finally`
            // removed after the chain is SUBMITTED; the propagating EC snapshots it onto each hop,
            // so the captured copy rides the async work even once this thread's MDC is cleared.
            MDC.put(CorrelationId.MdcKey, CorrelationId.adoptOrMint(message.correlationId))
            try
              // `Future.unit.flatMap` so a handler that throws *synchronously* (before returning
              // its Future) still folds into a failed Future here — keeping one message's failure
              // from aborting `Future.sequence` and losing its siblings' acks (per-message
              // isolation).
              Future.unit
                .flatMap(_ => handle(decoded))(tracingEc)
                .map(_ => Some(pulled.ackId))(tracingEc)
                .recover { case NonFatal(e) =>
                  log.warn(
                    "handler failed for message {} from {} ({}); leaving un-acked for retry",
                    pulled.ackId,
                    subscription,
                    e.getMessage
                  )
                  None
                }(tracingEc)
            finally MDC.remove(CorrelationId.MdcKey)
