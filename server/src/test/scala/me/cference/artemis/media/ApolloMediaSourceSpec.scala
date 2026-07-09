package me.cference.artemis.media

import apollostorage.grpc.{GetObjectResponse, ObjectMetadata}
import com.google.protobuf.ByteString as ProtoBytes
import io.grpc.{Status, StatusRuntimeException}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Unit tests for the header/body split that [[ApolloMediaSource]] performs on Apollo's `getObject`
 * stream — the leading `Header(ObjectMetadata)` carries content type + size, the trailing `Chunk`s
 * are the body. Exercised against crafted `Source[GetObjectResponse]`s, so no Apollo and no Docker
 * are needed; `ApolloMediaSource.split` is the extracted, testable seam.
 */
final class ApolloMediaSourceSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures:

  private given scala.concurrent.ExecutionContext = system.executionContext

  private def header(contentType: String, size: Long): GetObjectResponse =
    GetObjectResponse(
      GetObjectResponse.Payload.Header(
        ObjectMetadata(
          bucket = "media",
          `object` = "ab/abc/thumb.webp",
          contentType = contentType,
          size = size
        )
      )
    )

  private def chunk(s: String): GetObjectResponse =
    GetObjectResponse(GetObjectResponse.Payload.Chunk(ProtoBytes.copyFromUtf8(s)))

  "ApolloMediaSource.split" should {
    "peel the header's content type and size, then reassemble the chunk body" in {
      val responses = Source(List(header("image/webp", 9L), chunk("WEBP"), chunk("-body")))
      val media =
        ApolloMediaSource.split(responses).futureValue.getOrElse(fail("expected a media object"))
      media.contentType shouldBe "image/webp"
      media.size shouldBe Some(9L)
      media.bytes.runFold(ByteString.empty)(_ ++ _).futureValue shouldBe ByteString("WEBP-body")
    }

    "map a gRPC NOT_FOUND failure to None" in {
      val responses = Source.failed[GetObjectResponse](new StatusRuntimeException(Status.NOT_FOUND))
      ApolloMediaSource.split(responses).futureValue shouldBe None
    }

    "treat a header-less stream as no object (None)" in {
      ApolloMediaSource.split(Source.empty[GetObjectResponse]).futureValue shouldBe None
    }
  }
