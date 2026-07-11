package me.cference.artemis.domain

import me.cference.artemis.domain.PostCommand.*
import me.cference.artemis.domain.PostEvent.*
import me.cference.artemis.domain.PostState.Active
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

/**
 * Auto-tagging aggregate transitions (auto-tagging spec, tasks 1.1/1.2): recording Argus's
 * suggestions folds a pending set distinct from applied tags and flags review (idempotently);
 * accepting applies chosen tags via a `TagsChanged` + `SuggestionsReviewed` and reject-all still
 * reviews. Suggestions only exist on an active post.
 */
final class PostSuggestionsSpec extends AnyWordSpec with Matchers:

  private val now = Instant.parse("2026-07-10T00:00:00Z")
  private val postId = PostId.unsafe("post|1")
  private val md5 = Md5("abc123")
  private val phash = Phash("ph-1")
  private val filetype = Filetype("image/png")
  private val dims = Dimensions.unsafe(800, 600, None)
  private val derivatives = Vector(Derivative("sample", "blob://sample"))

  // `catgirl` aliases to `cat_girl`, which implies `animal_ears`.
  private val graph = TagGraph(
    aliases = Map(Tag.unsafe("catgirl") -> Tag.unsafe("cat_girl")),
    implications = Map(Tag.unsafe("cat_girl") -> Set(Tag.unsafe("animal_ears")))
  )

  private def sugg(tag: String, confidence: Double, source: String = "wd") =
    SuggestedTag(Tag.unsafe(tag), confidence, source)

  private def active: PostState =
    PostDomain.replay(
      Seq(PostCreated(postId, md5, filetype, now), MediaProcessed(dims, derivatives, phash, now))
    )

  private def contentOf(state: PostState): PostContent = state match
    case Active(_, _, content) => content
    case other => fail(s"expected Active, got $other")

  "Recording suggestions" should {

    "fold a pending suggestion set separate from applied tags, flagging review" in {
      val suggestions = Vector(sugg("cat_girl", 0.9), sugg("outdoors", 0.7))
      val Right(events) =
        PostDomain.decide(active, RecordSuggestions(suggestions, now), graph): @unchecked
      events shouldBe Seq(SuggestionsRecorded(suggestions, now))

      val content = contentOf(PostDomain.evolve(active, events.head))
      content.suggestions shouldBe suggestions
      content.needsReview shouldBe true
      content.tags shouldBe empty // suggestions are NOT applied tags
    }

    "be idempotent — re-recording the same set on a flagged post emits nothing" in {
      val suggestions = Vector(sugg("cat_girl", 0.9))
      val flagged = PostDomain.evolve(active, SuggestionsRecorded(suggestions, now))
      PostDomain.decide(flagged, RecordSuggestions(suggestions, now), graph) shouldBe Right(
        Seq.empty
      )
    }

    "replace the pending set when new suggestions differ" in {
      val first = Vector(sugg("cat_girl", 0.9))
      val flagged = PostDomain.evolve(active, SuggestionsRecorded(first, now))
      val second = Vector(sugg("cat_girl", 0.95), sugg("tree", 0.6))
      val Right(events) =
        PostDomain.decide(flagged, RecordSuggestions(second, now), graph): @unchecked
      events shouldBe Seq(SuggestionsRecorded(second, now))
      contentOf(PostDomain.evolve(flagged, events.head)).suggestions shouldBe second
    }

    "reject suggestions on a pending (not-yet-active) post" in {
      val pending = PostDomain.replay(Seq(PostCreated(postId, md5, filetype, now)))
      PostDomain.decide(pending, RecordSuggestions(Vector(sugg("x", 0.5)), now), graph) shouldBe
        Left(DomainError.PostNotFound)
    }
  }

  "Accepting suggestions" should {

    "apply chosen tags (canonicalized) via TagsChanged, then clear review" in {
      val flagged =
        PostDomain.evolve(active, SuggestionsRecorded(Vector(sugg("catgirl", 0.9)), now))
      // Accept the aliased surface form; it canonicalizes to cat_girl + implied animal_ears.
      val accept = AcceptSuggestions(Set(Tag.unsafe("catgirl")), now)
      val Right(events) = PostDomain.decide(flagged, accept, graph): @unchecked
      events shouldBe Seq(
        TagsChanged(Set(Tag.unsafe("cat_girl"), Tag.unsafe("animal_ears")), now),
        SuggestionsReviewed(now)
      )

      val reviewed = events.foldLeft(flagged)(PostDomain.evolve)
      val content = contentOf(reviewed)
      content.tags shouldBe Set(Tag.unsafe("cat_girl"), Tag.unsafe("animal_ears"))
      content.needsReview shouldBe false
      content.suggestions shouldBe empty
    }

    "union accepted tags with the already-applied set" in {
      val withTag = PostDomain.evolve(active, TagsChanged(Set(Tag.unsafe("solo")), now))
      val flagged = PostDomain.evolve(withTag, SuggestionsRecorded(Vector(sugg("tree", 0.8)), now))
      val accept = AcceptSuggestions(Set(Tag.unsafe("tree")), now)
      val Right(events) = PostDomain.decide(flagged, accept, graph): @unchecked
      events.head shouldBe TagsChanged(Set(Tag.unsafe("solo"), Tag.unsafe("tree")), now)
    }

    "reject-all (empty accept) still clears review without changing tags" in {
      val flagged = PostDomain.evolve(active, SuggestionsRecorded(Vector(sugg("tree", 0.8)), now))
      val Right(events) =
        PostDomain.decide(flagged, AcceptSuggestions(Set.empty, now), graph): @unchecked
      events shouldBe Seq(SuggestionsReviewed(now)) // no TagsChanged

      val content = contentOf(PostDomain.evolve(flagged, events.head))
      content.needsReview shouldBe false
      content.suggestions shouldBe empty
      content.tags shouldBe empty
    }

    "an explicit reject clears review and empties the pending set" in {
      val flagged = PostDomain.evolve(active, SuggestionsRecorded(Vector(sugg("tree", 0.8)), now))
      val Right(events) = PostDomain.decide(flagged, RejectSuggestions(now), graph): @unchecked
      events shouldBe Seq(SuggestionsReviewed(now))
      val content = contentOf(PostDomain.evolve(flagged, events.head))
      content.needsReview shouldBe false
      content.suggestions shouldBe empty
    }

    "rejecting an un-flagged post emits nothing" in {
      PostDomain.decide(active, RejectSuggestions(now), graph) shouldBe Right(Seq.empty)
    }
  }
