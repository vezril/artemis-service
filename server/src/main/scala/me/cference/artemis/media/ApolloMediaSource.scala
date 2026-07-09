package me.cference.artemis.media

import apollostorage.grpc.{GetObjectRequest, GetObjectResponse}
import codex.messages.v1.ObjectRef
import io.grpc.{Status, StatusRuntimeException}
import me.cference.artemis.grpc.ApolloObjectClient
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString

import scala.concurrent.{ExecutionContext, Future}

/**
 * The concrete [[MediaSource]] over Apollo. Apollo's `getObject` streams a leading
 * `Header(ObjectMetadata)` (content type + size) followed by `Chunk` messages (the body); this
 * splits the two so the route gets a content type/size up front and a lazy body stream it can slice
 * for range requests. A gRPC `NOT_FOUND` becomes `None` (a `404`); other failures propagate.
 */
final class ApolloMediaSource(client: ApolloObjectClient)(using
    mat: Materializer,
    ec: ExecutionContext
) extends MediaSource:

  def fetch(ref: ObjectRef): Future[Option[MediaObject]] =
    ApolloMediaSource.split(
      client.getObject(GetObjectRequest(bucket = ref.bucket, `object` = ref.`object`))
    )

object ApolloMediaSource:

  /**
   * Split Apollo's header-then-chunks `getObject` stream into a [[MediaObject]]. Uses
   * `prefixAndTail(1)` to peel the first message without buffering the body, reads the content type
   * and size from its `ObjectMetadata`, and exposes the trailing chunks as a lazy body `Source`
   * (protobuf `ByteString` → pekko `ByteString`). A leading message that is not a header —
   * including an empty stream — is treated as "no object" (`None`), as is a gRPC `NOT_FOUND`
   * failure. Any other failure propagates.
   */
  def split(responses: Source[GetObjectResponse, ?])(using
      mat: Materializer,
      ec: ExecutionContext
  ): Future[Option[MediaObject]] =
    responses
      .prefixAndTail(1)
      .runWith(Sink.head)
      .map { (prefix, tail) =>
        prefix.headOption.map(_.payload) match
          case Some(GetObjectResponse.Payload.Header(meta)) =>
            val body: Source[ByteString, ?] = tail.mapConcat { resp =>
              resp.payload match
                case GetObjectResponse.Payload.Chunk(bytes) =>
                  ByteString.fromArrayUnsafe(bytes.toByteArray) :: Nil
                case _ => Nil
            }
            Some(MediaObject(meta.contentType, Some(meta.size), body))
          case _ => None
      }
      .recover {
        case e: StatusRuntimeException if e.getStatus.getCode == Status.Code.NOT_FOUND => None
      }
