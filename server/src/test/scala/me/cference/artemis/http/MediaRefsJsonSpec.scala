package me.cference.artemis.http

import me.cference.artemis.domain.Derivative
import me.cference.artemis.projection.ReadModelRepository
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Unit tests for the shared media-ref derivation ([[DerivativeRef]]) and the projection-side JSON
 * parse ([[ReadModelRepository.parseDerivatives]]) — the two halves of the single
 * variant-derivation the search-projection path and the entity read path both go through. `variant`
 * MUST be the last path segment of the stored `<bucket>/<object>` ref (the media-gateway filename).
 */
final class MediaRefsJsonSpec extends AnyWordSpec with Matchers:

  "DerivativeRef.variantOf" should {
    "strip a stored ref to its last path segment" in {
      DerivativeRef.variantOf("media/ab/abc/thumb.webp") shouldBe "thumb.webp"
    }

    "strip the bucket prefix off a two-segment ref" in {
      DerivativeRef.variantOf("media/sample.webp") shouldBe "sample.webp"
    }

    "ignore an empty trailing segment" in {
      DerivativeRef.variantOf("media/ab/abc/thumb.webp/") shouldBe "thumb.webp"
    }

    "yield \"\" for a ref with no bucket/object separator (malformed), not the bucket name" in {
      // A well-formed ref is always <bucket>/<object>; anything with < 2 non-empty segments must
      // not surface the bucket (or a bare token) as a variant — that would build a 404ing URL.
      DerivativeRef.variantOf("media") shouldBe ""
      DerivativeRef.variantOf("media/") shouldBe ""
      DerivativeRef.variantOf("720p.mp4") shouldBe ""
      DerivativeRef.variantOf("") shouldBe ""
    }
  }

  "DerivativeRef.of" should {
    "carry the kind and derive the variant from a domain Derivative" in {
      DerivativeRef.of(Derivative("thumbnail", "media/ab/abc/thumb.webp")) shouldBe
        DerivativeRef("thumbnail", "thumb.webp")
    }
  }

  "DerivativeRef.refsOf" should {
    "map derivatives to wire refs, dropping any with an underivable variant" in {
      val derivatives = Seq(
        Derivative("thumbnail", "media/ab/abc/thumb.webp"),
        Derivative("broken", "media"), // bucket-only → dropped, never exposed
        Derivative("sample", "media/ab/abc/sample.webp")
      )
      DerivativeRef.refsOf(derivatives) shouldBe List(
        DerivativeRef("thumbnail", "thumb.webp"),
        DerivativeRef("sample", "sample.webp")
      )
    }
  }

  "ReadModelRepository.parseDerivatives" should {
    "parse [{kind, ref}] into domain derivatives" in {
      val json =
        """[{"kind":"thumbnail","ref":"media/ab/abc/thumb.webp"},
          | {"kind":"sample","ref":"media/ab/abc/sample.webp"}]""".stripMargin
      ReadModelRepository.parseDerivatives(json) should contain theSameElementsAs Seq(
        Derivative("thumbnail", "media/ab/abc/thumb.webp"),
        Derivative("sample", "media/ab/abc/sample.webp")
      )
    }

    "yield an empty seq for the default empty array" in {
      ReadModelRepository.parseDerivatives("[]") shouldBe empty
    }

    "skip malformed elements without throwing" in {
      val json = """[{"kind":"thumbnail"},{"ref":"media/ab/abc/x.webp"},"nope"]"""
      ReadModelRepository.parseDerivatives(json) shouldBe empty
    }
  }
