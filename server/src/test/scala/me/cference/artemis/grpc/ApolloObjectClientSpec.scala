package me.cference.artemis.grpc

import apollostorage.grpc.*
import com.google.protobuf.ByteString as ProtoBytes
import com.typesafe.config.ConfigFactory
import io.grpc.{Status, StatusRuntimeException}
import me.cference.artemis.config.{ApolloConfig, AppConfig}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.grpc.scaladsl.Metadata
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/**
 * Client-side seam over the shared Apollo gRPC stub (`apollostorage.grpc`, from the lexicon-grpc
 * jar — there is deliberately no `object_api.proto` in the Artemis tree). Config-construction is
 * asserted directly; the streaming methods are exercised against an in-process test double bound on
 * an ephemeral port, mirroring Apollo's own `GrpcServerSpec` handler+client pattern.
 */
final class ApolloObjectClientSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(
          """
        pekko.http.server.preview.enable-http2 = on
        # Don't let the shared runtime config's coordinated-shutdown kill the forked test JVM
        # when the testkit terminates this ActorSystem.
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
        """
        )
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(100, Millis))

  "AppConfig.apollo" should {
    "read host, port and tls from HOCON" in {
      val hocon = ConfigFactory
        .parseString("""artemis.apollo { host = "apollo.internal", port = 9443, tls = true }""")
        .resolve()
      val cfg = AppConfig.apollo(hocon)
      cfg.host shouldBe "apollo.internal"
      cfg.port shouldBe 9443
      cfg.useTls shouldBe true
    }
  }

  "ApolloObjectClient" should {
    "be constructed from an ApolloConfig with no hard-coded endpoint" in {
      // Uses the generated stub from the jar; compiling this at all proves it is on the classpath.
      val client = new ApolloObjectClient(ApolloConfig("localhost", 65535, useTls = false))
      client should not be null // scalafix:ok DisableSyntax.null
    }

    "put and get an object against an in-process Apollo double, preserving bytes" in {
      val fake = new FakeObjectApi
      val handler = ObjectApiPowerApiHandler(fake)
      val binding = Http()(system).newServerAt("127.0.0.1", 0).bind(handler).futureValue
      val port = binding.localAddress.getPort
      port should be > 0

      val client = new ApolloObjectClient(ApolloConfig("127.0.0.1", port, useTls = false))

      val payload = ByteString("hello apollo, some bytes")
      val chunks = Source(List(payload.take(5), payload.drop(5)))
      val header =
        PutHeader(bucket = "media", `object` = "p-1/source.png", contentType = "image/png")

      val putResponse = client.putObject(header, chunks).futureValue
      putResponse.size shouldBe payload.size.toLong

      val received = client
        .getObject(GetObjectRequest(bucket = "media", `object` = "p-1/source.png"))
        .runFold(ByteString.empty) { (acc, resp) =>
          resp.payload match
            case GetObjectResponse.Payload.Chunk(c) => acc ++ ByteString(c.toByteArray)
            case _ => acc
        }
        .futureValue

      received shouldBe payload
    }

    "surface a checksum/precondition rejection as a typed gRPC failure, not a swallowed success" in {
      // Apollo rejects a PutObject whose bytes fail its ingest checksum; the client must propagate a
      // StatusRuntimeException (distinguishable from a transport error) so the upload path can abort.
      val rejecting = new RejectingObjectApi
      val binding = Http()(system)
        .newServerAt("127.0.0.1", 0)
        .bind(ObjectApiPowerApiHandler(rejecting))
        .futureValue
      val client = new ApolloObjectClient(
        ApolloConfig("127.0.0.1", binding.localAddress.getPort, useTls = false)
      )

      val header =
        PutHeader(bucket = "media", `object` = "p-2/source.png", contentType = "image/png")
      val failure = client
        .putObject(header, Source.single(ByteString("bytes")))
        .failed
        .futureValue
      failure shouldBe a[StatusRuntimeException]
    }
  }

  /**
   * Rejects a put with a precondition failure, standing in for Apollo's checksum-mismatch abort.
   */
  final private class RejectingObjectApi extends FakeObjectApi:
    override def putObject(
        in: Source[PutObjectRequest, NotUsed],
        metadata: Metadata
    ): Future[PutObjectResponse] =
      Future.failed(new StatusRuntimeException(Status.FAILED_PRECONDITION))

  /** In-process stand-in for Apollo's ObjectApi: stores put bytes and streams them back on get. */
  private class FakeObjectApi extends ObjectApiPowerApi:
    private val store = TrieMap.empty[String, ProtoBytes]

    override def putObject(
        in: Source[PutObjectRequest, NotUsed],
        metadata: Metadata
    ): Future[PutObjectResponse] =
      in.runFold((Option.empty[PutHeader], ProtoBytes.EMPTY)) { (acc, req) =>
        req.payload match
          case PutObjectRequest.Payload.Header(h) => (Some(h), acc._2)
          case PutObjectRequest.Payload.Chunk(c) => (acc._1, acc._2.concat(c))
          case PutObjectRequest.Payload.Empty => acc
      }.map { (maybeHeader, bytes) =>
        val key = maybeHeader.map(_.`object`).getOrElse("")
        val _ = store.put(key, bytes)
        PutObjectResponse(generation = 1L, crc32C = "", md5 = "", size = bytes.size.toLong)
      }(system.executionContext)

    override def getObject(
        in: GetObjectRequest,
        metadata: Metadata
    ): Source[GetObjectResponse, NotUsed] =
      val bytes = store.getOrElse(in.`object`, ProtoBytes.EMPTY)
      val head = GetObjectResponse(
        GetObjectResponse.Payload.Header(
          ObjectMetadata(bucket = in.bucket, `object` = in.`object`, size = bytes.size.toLong)
        )
      )
      val chunk = GetObjectResponse(GetObjectResponse.Payload.Chunk(bytes))
      Source(List(head, chunk))

    private def unsupported[A]: Future[A] =
      Future.failed(new UnsupportedOperationException("not used by ApolloObjectClientSpec"))

    override def createBucket(in: CreateBucketRequest, metadata: Metadata): Future[BucketResponse] =
      unsupported
    override def deleteBucket(in: DeleteBucketRequest, metadata: Metadata): Future[BucketResponse] =
      unsupported
    override def headObject(in: HeadObjectRequest, metadata: Metadata): Future[ObjectMetadata] =
      unsupported
    override def deleteObject(
        in: DeleteObjectRequest,
        metadata: Metadata
    ): Future[DeleteObjectResponse] = unsupported
    override def listBuckets(
        in: ListBucketsRequest,
        metadata: Metadata
    ): Future[ListBucketsResponse] = unsupported
    override def listObjects(
        in: ListObjectsRequest,
        metadata: Metadata
    ): Future[ListObjectsResponse] = unsupported
