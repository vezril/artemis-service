package me.cference.artemis.media

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The content-addressed key convention that maps a `(md5, variant)` pair to the Apollo object it
 * lives under. This MUST match how Hephaestus/Apollo store originals and derivatives — see
 * [[MediaResolver]] — so it is pinned here as a behavior, not left implicit in the route.
 */
final class MediaResolverSpec extends AnyWordSpec with Matchers:

  "MediaResolver.resolve" should {
    "map (md5, variant) to the `media` bucket and Hephaestus's derivatives/ key layout" in {
      val ref = MediaResolver.resolve("abcdef0123", "thumb.webp")
      ref.bucket shouldBe "media"
      ref.`object` shouldBe "derivatives/ab/abcdef0123/thumb.webp"
    }

    "shard by the first two md5 characters for any variant filename" in {
      MediaResolver
        .resolve("ff991100", "720p.mp4")
        .`object` shouldBe "derivatives/ff/ff991100/720p.mp4"
    }
  }
