package me.cference.artemis.hermes

import codex.messages.v1.TagJob
import com.google.protobuf.ByteString as ProtoBytes
import me.cference.artemis.ingest.TagJobPublisher
import me.cference.artemis.messages.MediaMessages
import me.cference.artemis.tracing.CorrelationId
import me.cference.hermesmq.grpc.PublishRequest

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.{ExecutionContext, Future}

/**
 * The concrete auto-tag publisher (auto-tagging spec, task 3.1): encodes a `TagJob` as canonical
 * protobuf JSON (design-lexicon wire format) and publishes it to `topic` (the tag contract's
 * `media.tag`) via HermesMQ, so Argus picks up the just-activated post's sample and suggests tags.
 * Fire-and-forget (`Future[Unit]`): the response's messageId/dedup flag is discarded, and the
 * caller treats publish as best-effort — tagging is decoupled and must never block ingest.
 */
final class HermesTagJobPublisher(client: HermesClient, topic: String)(using ec: ExecutionContext)
    extends TagJobPublisher:

  def publish(job: TagJob): Future[Unit] =
    client
      .publish(
        PublishRequest(
          topicId = topic,
          payload = ProtoBytes.copyFrom(MediaMessages.toJson(job), UTF_8),
          // Propagate the current correlation id onto the published tag-job (request-tracing).
          correlationId = CorrelationId.current().getOrElse(""),
          // Identify Artemis to the broker's per-producer observability (metrics + console).
          producerId = HermesIdentity.ProducerId
        )
      )
      .map(_ => ())
