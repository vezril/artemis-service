package me.cference.artemis.hermes

import me.cference.artemis.config.HermesConfig
import me.cference.hermesmq.grpc.{
  AckRequest,
  AckResponse,
  PublishRequest,
  PublishResponse,
  PubSubServiceClient,
  PullRequest,
  PullResponse
}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.GrpcClientSettings

import scala.concurrent.Future

/**
 * Thin seam over the shared HermesMQ `PubSubServiceClient` (lexicon-hermes-grpc jar). Constructs
 * the stub from a [[HermesConfig]] so the endpoint is configured, never hard-coded, and exposes
 * only the three unary ops the media adapters need — publish (upload spine) and pull/ack (consume
 * spine). Carries no media business logic; that lives in [[HermesMediaJobPublisher]] /
 * [[HermesMediaResultConsumer]].
 *
 * Intended as an app-lifetime singleton: the underlying gRPC channel registers with the system's
 * `CoordinatedShutdown`, so it is released on ActorSystem termination — do not construct one per
 * request. Mirrors [[me.cference.artemis.grpc.ApolloObjectClient]].
 */
final class HermesClient(config: HermesConfig)(using system: ActorSystem[?]):

  private val settings: GrpcClientSettings =
    GrpcClientSettings
      .connectToServiceAt(config.host, config.port)(system)
      .withTls(config.useTls)

  private val client: PubSubServiceClient = PubSubServiceClient(settings)(system)

  def publish(request: PublishRequest): Future[PublishResponse] = client.publish(request)

  def pull(request: PullRequest): Future[PullResponse] = client.pull(request)

  def ack(request: AckRequest): Future[AckResponse] = client.ack(request)
