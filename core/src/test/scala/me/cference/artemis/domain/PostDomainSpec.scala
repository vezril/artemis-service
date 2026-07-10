package me.cference.artemis.domain

import me.cference.artemis.domain.PostCommand.*
import me.cference.artemis.domain.PostEvent.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

final class PostDomainSpec extends AnyWordSpec with Matchers:

  private val now = Instant.parse("2026-07-08T00:00:00Z")
  private val postId = PostId.unsafe("post|1")
  private val md5 = Md5("abc123")
  private val phash = Phash("ph-1")
  private val filetype = Filetype("image/png")
  private val dims = Dimensions.unsafe(800, 600, None)
  private val derivatives = Vector(Derivative("thumbnail", "blob://thumb"))
  private val graph = TagGraph(
    aliases = Map(Tag.unsafe("catgirl") -> Tag.unsafe("cat_girl")),
    implications = Map(Tag.unsafe("cat_girl") -> Set(Tag.unsafe("animal_ears")))
  )

  private def created: PostState =
    PostDomain.replay(Seq(PostCreated(postId, md5, filetype, now)))

  private def active: PostState =
    PostDomain.replay(
      Seq(
        PostCreated(postId, md5, filetype, now),
        MediaProcessed(dims, derivatives, phash, now)
      )
    )

  "PostDomain lifecycle" should {

    "create a post from Empty and fold to Pending" in {
      val Right(events) =
        PostDomain.decide(PostState.Empty, CreatePost(postId, md5, filetype, now)): @unchecked
      events shouldBe Seq(PostCreated(postId, md5, filetype, now))
      PostDomain.replay(events) shouldBe PostState.Pending(postId, md5, filetype)
    }

    "record processing on a pending post and move it to Active" in {
      val Right(events) =
        PostDomain.decide(created, RecordProcessed(dims, derivatives, phash, now)): @unchecked
      events shouldBe Seq(MediaProcessed(dims, derivatives, phash, now))
      PostDomain.replay(
        Seq(PostCreated(postId, md5, filetype, now)) ++ events
      ) shouldBe a[PostState.Active]
    }

    "reject Delete on a pending post with zero events (nothing to tombstone yet)" in {
      // A pending post has no processed media; the Deleted tombstone carries media/content,
      // so a pending post is not deletable — decide must reject rather than emit a PostDeleted
      // that evolve would silently drop (leaving a phantom-deleted post on replay).
      PostDomain.decide(created, Delete(now)) shouldBe Left(DomainError.PostNotFound)
    }

    "reject a duplicate CreatePost with zero events" in {
      PostDomain.decide(created, CreatePost(postId, md5, filetype, now)) shouldBe Left(
        DomainError.PostAlreadyExists
      )
    }

    "reject any command on a non-existent post" in {
      PostDomain.decide(PostState.Empty, RecordProcessed(dims, derivatives, phash, now)) shouldBe
        Left(DomainError.PostNotFound)
    }

    "delete an active post, folding to Deleted" in {
      val Right(events) = PostDomain.decide(active, Delete(now)): @unchecked
      events shouldBe Seq(PostDeleted(now))
      PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          MediaProcessed(dims, derivatives, phash, now)
        ) ++ events
      ) shouldBe a[PostState.Deleted]
    }

    "restore a deleted post back to active" in {
      val deleted = PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          MediaProcessed(dims, derivatives, phash, now),
          PostDeleted(now)
        )
      )
      val Right(events) = PostDomain.decide(deleted, Restore(now)): @unchecked
      events shouldBe Seq(PostRestored(now))
      PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          MediaProcessed(dims, derivatives, phash, now),
          PostDeleted(now),
          PostRestored(now)
        )
      ) shouldBe a[PostState.Active]
    }

    "reject a non-Restore command on a deleted post with zero events" in {
      val deleted = PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          MediaProcessed(dims, derivatives, phash, now),
          PostDeleted(now)
        )
      )
      PostDomain.decide(deleted, RecordProcessed(dims, derivatives, phash, now)) shouldBe
        Left(DomainError.PostNotFound)
    }

    "reject ChangeTags on a deleted post with zero events" in {
      val deleted = PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          MediaProcessed(dims, derivatives, phash, now),
          PostDeleted(now)
        )
      )
      PostDomain.decide(deleted, ChangeTags(Set(Tag.unsafe("solo")), now), TagGraph.empty) shouldBe
        Left(DomainError.PostNotFound)
    }
  }

  "PostDomain failed processing lifecycle" should {

    "mark a pending post failed, folding to Failed" in {
      val Right(events) = PostDomain.decide(created, MarkFailed("decode error", now)): @unchecked
      events shouldBe Seq(ProcessingFailed("decode error", now))
      PostDomain.replay(
        Seq(PostCreated(postId, md5, filetype, now)) ++ events
      ) shouldBe PostState.Failed(postId, md5, filetype, "decode error")
    }

    "reject a command on a failed post with zero events (terminal state)" in {
      val failed = PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          ProcessingFailed("decode error", now)
        )
      )
      PostDomain.decide(failed, RecordProcessed(dims, derivatives, phash, now)) shouldBe
        Left(DomainError.PostNotFound)
    }

    "reject MarkFailed on an active post with zero events (only a pending post can fail)" in {
      PostDomain.decide(active, MarkFailed("too late", now)) shouldBe
        Left(DomainError.PostNotFound)
    }

    "accept MarkFailed on an already-failed post as an idempotent no-op" in {
      // Redelivered MediaFailed must not surface a spurious rejection — the terminal transition is
      // idempotent in the domain (a re-mark emits nothing), independent of any consumer dedup guard.
      val failed = PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          ProcessingFailed("decode error", now)
        )
      )
      PostDomain.decide(failed, MarkFailed("again", now)) shouldBe Right(Seq.empty)
    }
  }

  "PostDomain ChangeTags canonicalization" should {

    "canonicalize the requested tags before emitting TagsChanged" in {
      val Right(events) =
        PostDomain.decide(active, ChangeTags(Set(Tag.unsafe("catgirl")), now), graph): @unchecked
      events shouldBe Seq(
        TagsChanged(Set(Tag.unsafe("cat_girl"), Tag.unsafe("animal_ears")), now)
      )
    }

    "fold the canonical tag set into the active post's content" in {
      val Right(events) =
        PostDomain.decide(active, ChangeTags(Set(Tag.unsafe("catgirl")), now), graph): @unchecked
      events.foldLeft(active)(PostDomain.evolve) match
        case PostState.Active(_, _, content) =>
          content.tags shouldBe Set(Tag.unsafe("cat_girl"), Tag.unsafe("animal_ears"))
        case other => fail(s"expected Active, got $other")
    }

    "keep the TagsChanged sequence as recoverable edit history" in {
      val edits = Seq(
        TagsChanged(Set(Tag.unsafe("solo")), now),
        TagsChanged(Set(Tag.unsafe("solo"), Tag.unsafe("cat_girl")), now),
        TagsChanged(Set(Tag.unsafe("cat_girl")), now)
      )
      val history = edits.map(_.tags)
      val base = Seq(
        PostCreated(postId, md5, filetype, now),
        MediaProcessed(dims, derivatives, phash, now)
      )
      // Each prefix of the journal replays to the tag set current at that point — no lookup.
      edits.indices.foreach { i =>
        PostDomain.replay(base ++ edits.take(i + 1)) match
          case PostState.Active(_, _, content) => content.tags shouldBe history(i)
          case other => fail(s"expected Active, got $other")
      }
    }
  }

  "PostDomain possible-duplicate flag" should {

    "emit PossibleDuplicateFlagged for an active post and fold the reference into content" in {
      val matched = PostId.unsafe("post|42")
      val Right(events) =
        PostDomain.decide(active, FlagPossibleDuplicate(matched, now)): @unchecked
      events shouldBe Seq(PossibleDuplicateFlagged(matched, now))
      events.foldLeft(active)(PostDomain.evolve) match
        case PostState.Active(_, _, content) => content.duplicateOf shouldBe Some(matched)
        case other => fail(s"expected Active, got $other")
    }

    "treat re-flagging with the same matched id as an idempotent no-op" in {
      val matched = PostId.unsafe("post|42")
      val flagged = PostDomain.decide(active, FlagPossibleDuplicate(matched, now)) match
        case Right(evs) => evs.foldLeft(active)(PostDomain.evolve)
        case Left(err) => fail(err.message)
      PostDomain.decide(flagged, FlagPossibleDuplicate(matched, now)) shouldBe Right(Seq.empty)
    }

    "re-flag when the matched id differs from the one already recorded" in {
      val first = PostId.unsafe("post|42")
      val second = PostId.unsafe("post|99")
      val flagged = PostDomain.decide(active, FlagPossibleDuplicate(first, now)) match
        case Right(evs) => evs.foldLeft(active)(PostDomain.evolve)
        case Left(err) => fail(err.message)
      PostDomain.decide(flagged, FlagPossibleDuplicate(second, now)) shouldBe
        Right(Seq(PossibleDuplicateFlagged(second, now)))
    }

    "reject FlagPossibleDuplicate on a pending post with zero events" in {
      PostDomain.decide(created, FlagPossibleDuplicate(PostId.unsafe("post|42"), now)) shouldBe
        Left(DomainError.PostNotFound)
    }
  }

  "PostDomain rating, relationships, favorites, and scores" should {

    "emit RatingChanged for a valid rating and fold it into content" in {
      val Right(events) = PostDomain.decide(active, SetRating("s", now)): @unchecked
      events shouldBe Seq(RatingChanged(Rating.Sensitive, now))
      events.foldLeft(active)(PostDomain.evolve) match
        case PostState.Active(_, _, content) => content.rating shouldBe Some(Rating.Sensitive)
        case other => fail(s"expected Active, got $other")
    }

    "reject a rating outside {g,s,q,e} with zero events" in {
      PostDomain.decide(active, SetRating("x", now)) shouldBe
        Left(DomainError.InvalidRating("must be one of g, s, q, e; was 'x'"))
    }

    "emit ParentSet and fold the parent into content" in {
      val parent = PostId.unsafe("post|9")
      val Right(events) = PostDomain.decide(active, SetParent(parent, now)): @unchecked
      events shouldBe Seq(ParentSet(parent, now))
      events.foldLeft(active)(PostDomain.evolve) match
        case PostState.Active(_, _, content) => content.parent shouldBe Some(parent)
        case other => fail(s"expected Active, got $other")
    }

    "emit Favorited the first time and treat re-favorite as an idempotent no-op" in {
      val Right(first) = PostDomain.decide(active, Favorite(now)): @unchecked
      first shouldBe Seq(Favorited(now))
      val favorited = first.foldLeft(active)(PostDomain.evolve)
      PostDomain.decide(favorited, Favorite(now)) shouldBe Right(Seq.empty)
    }

    "emit Unfavorited when a favorited post is unfavorited" in {
      val favorited = PostDomain.decide(active, Favorite(now)) match
        case Right(evs) => evs.foldLeft(active)(PostDomain.evolve)
        case Left(err) => fail(err.message)
      val Right(events) = PostDomain.decide(favorited, Unfavorite(now)): @unchecked
      events shouldBe Seq(Unfavorited(now))
      events.foldLeft(favorited)(PostDomain.evolve) match
        case PostState.Active(_, _, content) => content.favorited shouldBe false
        case other => fail(s"expected Active, got $other")
    }

    "treat unfavoriting a non-favorited post as an idempotent no-op" in {
      PostDomain.decide(active, Unfavorite(now)) shouldBe Right(Seq.empty)
    }

    "emit Scored carrying the absolute new total and fold it into content" in {
      val Right(events) = PostDomain.decide(active, Score(5, now)): @unchecked
      // Score(delta) on a fresh post (score 0) yields the absolute total Scored(5).
      events shouldBe Seq(Scored(5, now))
      val scored = events.foldLeft(active)(PostDomain.evolve)
      scored match
        case PostState.Active(_, _, content) => content.score shouldBe 5
        case other => fail(s"expected Active, got $other")

      // A subsequent Score(3) accumulates from the current total: Scored(8), not Scored(3).
      val Right(more) = PostDomain.decide(scored, Score(3, now)): @unchecked
      more shouldBe Seq(Scored(8, now))
      more.foldLeft(scored)(PostDomain.evolve) match
        case PostState.Active(_, _, content) => content.score shouldBe 8
        case other => fail(s"expected Active, got $other")
    }

    "emit SourceChanged and fold the source into content" in {
      val Right(events) =
        PostDomain.decide(active, SetSource("https://example.test/x", now)): @unchecked
      events shouldBe Seq(SourceChanged("https://example.test/x", now))
      events.foldLeft(active)(PostDomain.evolve) match
        case PostState.Active(_, _, content) =>
          content.source shouldBe Some("https://example.test/x")
        case other => fail(s"expected Active, got $other")
    }
  }
