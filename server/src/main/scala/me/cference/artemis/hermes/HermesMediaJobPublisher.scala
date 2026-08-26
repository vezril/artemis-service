package me.cference.artemis.hermes

import codex.messages.v1.ProcessMediaJob
import com.google.protobuf.ByteString as ProtoBytes
import me.cference.artemis.ingest.MediaJobPublisher
import me.cference.artemis.messages.MediaMessages
import me.cference.artemis.tracing.CorrelationId
import me.cference.hermesmq.grpc.PublishRequest

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.{ExecutionContext, Future}

/**
 * The concrete upload-spine publisher (OpenSpec task 4.1): encodes a `ProcessMediaJob` as canonical
 * protobuf JSON (design-lexicon wire format) and publishes it to the `topic` (the media contract's
 * `media.process`) via HermesMQ. The response's messageId/dedup flag is intentionally discarded —
 * the port's contract is a fire-and-forget `Future[Unit]`; Hermes idempotency is a later concern.
 */
final class HermesMediaJobPublisher(client: HermesClient, topic: String)(using ec: ExecutionContext)
    extends MediaJobPublisher:

  def publish(job: ProcessMediaJob): Future[Unit] =
    client
      .publish(
        PublishRequest(
          topicId = topic,
          payload = ProtoBytes.copyFrom(MediaMessages.toJson(job), UTF_8),
          // Propagate the current correlation id onto the published job (request-tracing) so the
          // bus carries it to the consumer (Hephaestus), stitching ingest → process. Empty = none.
          correlationId = CorrelationId.current().getOrElse(""),
          // Identify Artemis to the broker's per-producer observability (metrics + console).
          producerId = HermesIdentity.ProducerId
        )
      )
      .map(_ => ())
