package me.cference.artemis.tracing

import org.apache.pekko.grpc.scaladsl.MetadataBuilder
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.MDC

/**
 * Unit coverage for correlation-id minting, the round-trip through gRPC metadata, and the
 * adopt-or-mint / current-from-MDC behaviours (request-tracing capability). IDs must be unique,
 * non-empty, and log-friendly; the canonical key names come from the shared Lexicon constants; the
 * id stamped onto request metadata must read back under the metadata key.
 */
final class CorrelationIdSpec extends AnyWordSpec with Matchers:

  "CorrelationId.mint" should {

    "produce a non-empty, log-friendly token" in {
      val id = CorrelationId.mint()
      id should not be empty
      id should fullyMatch regex "[0-9a-z]+"
      id.length shouldBe 12
    }

    "produce a distinct value each call" in {
      val ids = Vector.fill(1000)(CorrelationId.mint())
      ids.distinct.size shouldBe ids.size
    }
  }

  "CorrelationId names" should {
    "use the canonical MDC / header / metadata keys from the shared Lexicon constants" in {
      CorrelationId.MdcKey shouldBe "correlationId"
      CorrelationId.HttpHeader shouldBe "X-Correlation-Id"
      CorrelationId.MetadataKey shouldBe "x-correlation-id" // HTTP/2 keys must be lower-case
    }

    "round-trip through gRPC metadata under the metadata key" in {
      val id = CorrelationId.mint()
      val md = new MetadataBuilder().addText(CorrelationId.MetadataKey, id).build()
      md.getText(CorrelationId.MetadataKey) shouldBe Some(id)
    }
  }

  "CorrelationId.current" should {

    "read the id from the MDC when set" in {
      MDC.put(CorrelationId.MdcKey, "in-context")
      try CorrelationId.current() shouldBe Some("in-context")
      finally MDC.remove(CorrelationId.MdcKey)
    }

    "be None when the MDC has no (or an empty) id" in {
      MDC.remove(CorrelationId.MdcKey)
      CorrelationId.current() shouldBe None
      MDC.put(CorrelationId.MdcKey, "")
      try CorrelationId.current() shouldBe None
      finally MDC.remove(CorrelationId.MdcKey)
    }
  }

  "CorrelationId.adoptOrMint" should {

    "adopt a delivered non-empty id verbatim" in {
      CorrelationId.adoptOrMint("delivered-42") shouldBe "delivered-42"
    }

    "mint a fresh id when the delivery carries none" in {
      val minted = CorrelationId.adoptOrMint("")
      minted should not be empty
      minted.length shouldBe 12
    }
  }
