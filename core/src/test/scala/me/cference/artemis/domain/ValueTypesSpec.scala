package me.cference.artemis.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class ValueTypesSpec extends AnyWordSpec with Matchers:

  "Tag.from" should {
    "normalize mixed case and spacing to lowercase underscores" in {
      Tag.from("Cat Ears").map(_.value) shouldBe Right("cat_ears")
    }

    "collapse repeated separators to a single underscore" in {
      Seq("cat__ears", "  cat ears  ", "cat_ears").map(Tag.from(_).map(_.value)) shouldBe
        Seq(Right("cat_ears"), Right("cat_ears"), Right("cat_ears"))
    }

    "reject a name that is empty after normalization" in {
      Tag.from("   ") shouldBe Left(DomainError.InvalidTag("must not be empty after normalization"))
    }

    "reject a name exceeding the maximum length" in {
      val oversized = "a" * (Tag.MaxLength + 1)
      Tag.from(oversized) shouldBe Left(
        DomainError.InvalidTag(
          s"must be at most ${Tag.MaxLength} characters, was ${oversized.length}"
        )
      )
    }

    "not expose a public apply bypassing normalization" in {
      assertDoesNotCompile("""me.cference.artemis.domain.Tag("NotNormalized")""")
    }

    "not expose a public copy" in {
      assertDoesNotCompile(
        """me.cference.artemis.domain.Tag.from("x").toOption.get.copy(value = "y")"""
      )
    }
  }

  "Rating.from" should {
    "accept each of g, s, q, e" in {
      Seq("g", "s", "q", "e").map(Rating.from) shouldBe
        Seq(
          Right(Rating.General),
          Right(Rating.Sensitive),
          Right(Rating.Questionable),
          Right(Rating.Explicit)
        )
    }

    "reject a value outside the fixed set" in {
      Rating.from("x") shouldBe Left(
        DomainError.InvalidRating("must be one of g, s, q, e; was 'x'")
      )
    }
  }

  "Dimensions.from" should {
    "accept positive extents with an optional duration" in {
      Dimensions.from(1920, 1080, Some(5000L)).map(d => (d.width, d.height, d.duration)) shouldBe
        Right((1920, 1080, Some(5000L)))
    }

    "reject non-positive width or height" in {
      Dimensions.from(0, 100) shouldBe Left(
        DomainError.InvalidDimensions("width and height must be positive, was 0x100")
      )
    }

    "reject a negative duration" in {
      Dimensions.from(10, 10, Some(-1L)) shouldBe Left(
        DomainError.InvalidDimensions("duration must be non-negative")
      )
    }
  }

  "PostId.from and PoolId.from" should {
    "reject empty ids" in {
      PostId.from("  ") shouldBe Left(DomainError.InvalidPostId("must not be empty"))
      PoolId.from("  ") shouldBe Left(DomainError.InvalidPoolId("must not be empty"))
    }

    "accept and trim a valid id" in {
      PostId.from(" post|1 ").map(_.value) shouldBe Right("post|1")
    }
  }

  "PoolName.from" should {
    "reject an empty name and accept a valid one" in {
      PoolName.from("   ") shouldBe Left(DomainError.InvalidPoolName("must not be empty"))
      PoolName.from(" Summer 2026 ").map(_.value) shouldBe Right("Summer 2026")
    }
  }
