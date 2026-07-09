package me.cference.artemis.grpc

import apollostorage.grpc.*
import com.google.protobuf.ByteString as ProtoBytes
import me.cference.artemis.config.ApolloConfig
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.Future

/**
 * Thin seam over the shared Apollo `ObjectApiClient` (lexicon-grpc jar). Constructs the stub from
 * an [[ApolloConfig]] so the endpoint is configured, never hard-coded, and exposes only the two
 * header-then-chunks streaming calls Artemis needs later. Deliberately carries no upload/gateway
 * business logic — that arrives in a later milestone.
 *
 * Intended as an app-lifetime singleton: the underlying gRPC channel registers with the system's
 * `CoordinatedShutdown`, so it is released on ActorSystem termination — do not construct one per
 * request.
 */
final class ApolloObjectClient(config: ApolloConfig)(using system: ActorSystem[?]):

  private val settings: GrpcClientSettings =
    GrpcClientSettings
      .connectToServiceAt(config.host, config.port)(system)
      .withTls(config.useTls)

  private val client: ObjectApiClient = ObjectApiClient(settings)(system)

  /**
   * Stream a put: the header message first, then the payload chunks, per the object-api framing
   * (design D7/D16).
   */
  def putObject(header: PutHeader, chunks: Source[ByteString, ?]): Future[PutObjectResponse] =
    val headerMsg = PutObjectRequest(PutObjectRequest.Payload.Header(header))
    val chunkMsgs = chunks.map(bytes =>
      // Single copy pekko ByteString -> protobuf ByteString (asByteBuffer avoids a `.toArray` copy).
      PutObjectRequest(PutObjectRequest.Payload.Chunk(ProtoBytes.copyFrom(bytes.asByteBuffer)))
    )
    client.putObject(Source.single(headerMsg).concat(chunkMsgs))

  /** Stream a get: Apollo emits a header message then the object's chunks. */
  def getObject(req: GetObjectRequest): Source[GetObjectResponse, NotUsed] =
    client.getObject(req)
