package me.cference.artemis.projection

import me.cference.artemis.domain.Derivative
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The media gateway's PRIMARY resolution: match a requested `variant` against the post's STORED
 * `<bucket>/<object>` derivative refs — the exact keys Hephaestus reported — never a reconvened key
 * convention (the convention once drifted and every image 404'd).
 */
final class MediaRefLookupSpec extends AnyWordSpec with Matchers:

  private val md5 = "abcdef0123456789"
  private val stored = Seq(
    Derivative("thumbnail", s"media/derivatives/ab/$md5/thumb.webp"),
    Derivative("sample", s"media/derivatives/ab/$md5/sample.webp"),
    Derivative("transcode", s"media/derivatives/ab/$md5/720p.mp4")
  )

  "ReadModelRepository.mediaRefFor" should {
    "return the stored bucket/object whose last segment matches the variant" in {
      ReadModelRepository.mediaRefFor(stored, "thumb.webp") shouldBe
        Some(("media", s"derivatives/ab/$md5/thumb.webp"))
      ReadModelRepository.mediaRefFor(stored, "720p.mp4") shouldBe
        Some(("media", s"derivatives/ab/$md5/720p.mp4"))
    }

    "return None when no derivative matches the variant" in {
      ReadModelRepository.mediaRefFor(stored, "original.png") shouldBe None
    }

    "never match on a partial segment and never throw on malformed refs" in {
      // "webp" is a suffix of the filename, not a full segment — no match.
      ReadModelRepository.mediaRefFor(stored, "webp") shouldBe None
      val malformed = Seq(Derivative("thumbnail", ""), Derivative("sample", "media/"))
      ReadModelRepository.mediaRefFor(malformed, "thumb.webp") shouldBe None
    }

    "work for any storage layout, since it echoes the stored key verbatim" in {
      // If Hephaestus ever re-keys (the exact failure mode this lookup exists to absorb),
      // the stored ref remains authoritative.
      val rekeyed = Seq(Derivative("thumbnail", s"assets/v2/$md5-thumb.webp"))
      ReadModelRepository.mediaRefFor(rekeyed, s"$md5-thumb.webp") shouldBe
        Some(("assets", s"v2/$md5-thumb.webp"))
    }
  }
