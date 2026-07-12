package me.cference.artemis.domain

import me.cference.artemis.domain.PostCommand.*
import me.cference.artemis.domain.PostEvent.*
import me.cference.artemis.domain.PostState.Active
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

/**
 * Version-stamp transitions (processing-versions spec, task 1.1): `RecordProcessed` stamps the
 * post's `derivativeSpecVersion` from what Hephaestus ran, and `RecordSuggestions` stamps
 * `taggerVersion` — independently. Reprocessing re-stamps on the next result.
 */
final class PostVersionStampSpec extends AnyWordSpec with Matchers:

  private val now = Instant.parse("2026-07-11T00:00:00Z")
  private val postId = PostId.unsafe("post|1")
  private val md5 = Md5("abc123")
  private val filetype = Filetype("image/png")
  private val dims = Dimensions.unsafe(8, 6, None)
  private val derivs = Vector(Derivative("sample", "r"))
  private val phash = Phash("p")

  private def mediaOf(state: PostState): PostMedia = state match
    case Active(_, media, _) => media
    case other => fail(s"expected Active, got $other")

  private def contentOf(state: PostState): PostContent = state match
    case Active(_, _, content) => content
    case other => fail(s"expected Active, got $other")

  "RecordProcessed" should {

    "stamp the derivativeSpecVersion Hephaestus ran" in {
      val state = PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          MediaProcessed(dims, derivs, phash, now, specVersion = 4)
        )
      )
      mediaOf(state).derivativeSpecVersion shouldBe 4
    }

    "re-stamp the version when a post is reprocessed" in {
      val v3 = PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          MediaProcessed(dims, derivs, phash, now, specVersion = 3)
        )
      )
      mediaOf(v3).derivativeSpecVersion shouldBe 3
      // Reprocess: a fresh MediaProcessed at v4 refreshes the stamp, keeping content.
      val v4 = PostDomain.evolve(v3, MediaProcessed(dims, derivs, phash, now, specVersion = 4))
      mediaOf(v4).derivativeSpecVersion shouldBe 4
    }

    "default the stamp to 0 for a pre-versioning event" in {
      val state = PostDomain.replay(
        Seq(PostCreated(postId, md5, filetype, now), MediaProcessed(dims, derivs, phash, now))
      )
      mediaOf(state).derivativeSpecVersion shouldBe 0
    }

    "preserve existing derivatives on a metadata-only reprocess (empty result)" in {
      // A metadata reprocess returns NO derivatives — it must not wipe the post's thumb/sample.
      val active = PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          MediaProcessed(dims, derivs, phash, now, specVersion = 3)
        )
      )
      mediaOf(active).derivatives shouldBe derivs
      val afterMetadata =
        PostDomain.evolve(active, MediaProcessed(dims, Vector.empty, Phash("newp"), now, 4))
      val media = mediaOf(afterMetadata)
      media.derivatives shouldBe derivs // preserved, not wiped
      media.phash shouldBe Phash("newp") // metadata (phash) still refreshed
      media.derivativeSpecVersion shouldBe 4 // and the stamp advances
    }
  }

  "RecordSuggestions" should {

    "stamp the taggerVersion independently of the derivative version" in {
      val active = PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          MediaProcessed(dims, derivs, phash, now, specVersion = 4)
        )
      )
      val Right(events) =
        PostDomain.decide(
          active,
          RecordSuggestions(
            Vector(SuggestedTag(Tag.unsafe("t"), 0.9, "wd")),
            now,
            taggerVersion = 2
          )
        ): @unchecked
      val reviewed = events.foldLeft(active)(PostDomain.evolve)
      contentOf(reviewed).taggerVersion shouldBe 2
      mediaOf(reviewed).derivativeSpecVersion shouldBe 4 // unchanged
    }

    "re-record when only the tagger version bumped (a re-tag with a new model)" in {
      val active = PostDomain.replay(
        Seq(PostCreated(postId, md5, filetype, now), MediaProcessed(dims, derivs, phash, now))
      )
      val sug = Vector(SuggestedTag(Tag.unsafe("t"), 0.9, "wd"))
      val v1 = PostDomain.evolve(active, SuggestionsRecorded(sug, now, taggerVersion = 1))
      // Same suggestions but a newer tagger version → NOT a no-op; it re-stamps.
      PostDomain.decide(v1, RecordSuggestions(sug, now, taggerVersion = 2)) shouldBe
        Right(Seq(SuggestionsRecorded(sug, now, 2)))
    }
  }
