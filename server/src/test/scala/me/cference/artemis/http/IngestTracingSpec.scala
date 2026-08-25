package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.domain.PostId
import me.cference.artemis.ingest.UploadResult
import me.cference.artemis.tracing.{CorrelationId, MdcPropagatingExecutionContext}
import org.apache.pekko.http.scaladsl.model.{ContentType, HttpEntity, MediaTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

/**
 * End-to-end ingest trace (request-tracing task 6.1): a `POST /uploads` routed through
 * [[RequestTracing.withCorrelationId]] mints a correlation id, and that id is the one the ingest
 * path reads via [[CorrelationId.current]] — both synchronously (as the route directive submits the
 * upload) and after an async hop on an [[MdcPropagatingExecutionContext]] (as
 * [[me.cference.artemis.ingest.UploadService]] does before it publishes the `ProcessMediaJob` and
 * calls Apollo). The same id is echoed back as `X-Correlation-Id`, so the outbound propagation and
 * the client-visible header provably agree.
 */
final class IngestTracingSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  override def testConfig =
    ConfigFactory
      .parseString("""
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(ConfigFactory.load())

  private val png = ContentType.Binary(MediaTypes.`image/png`)

  "POST /uploads through the tracing wrapper" should {

    "read the minted correlation id on the ingest path (sync + across an async hop), matching the echoed header" in {
      // Snapshot at submit time (synchronous route run) and after a Future hop on the propagating EC
      // — the two points at which UploadService would stamp the published job and the Apollo call.
      val propagating: ExecutionContext = MdcPropagatingExecutionContext(system.dispatcher)
      val syncSeen = new AtomicReference[Option[String]](None)
      val asyncSeen = new AtomicReference[Option[String]](None)

      val upload: (Source[ByteString, ?], String, String) => Future[UploadResult] =
        (_, _, _) =>
          syncSeen.set(CorrelationId.current()) // id present while the route directive runs
          Future(())(propagating).map { _ =>
            asyncSeen.set(CorrelationId.current()) // id survived the async boundary
            UploadResult(PostId.unsafe("up-trace"), "pending")
          }(propagating)

      val route = RequestTracing.withCorrelationId(new UploadRoutes(upload).routes)

      Post("/uploads", HttpEntity(png, ByteString("trace me"))) ~> route ~> check {
        status shouldBe StatusCodes.Created
        val echoed = header(CorrelationId.HttpHeader).map(_.value)
        echoed.map(_.length) shouldBe Some(12)
        syncSeen.get() shouldBe echoed
        asyncSeen.get() shouldBe echoed
      }
    }
  }
