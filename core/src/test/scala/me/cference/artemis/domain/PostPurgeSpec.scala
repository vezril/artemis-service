package me.cference.artemis.domain

import me.cference.artemis.domain.PostCommand.*
import me.cference.artemis.domain.PostEvent.*
import me.cference.artemis.domain.PostState.Purged
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

/**
 * Purge transitions (deletion-lifecycle): a soft-deleted post is permanently purged after its
 * retention window (`Deleted → Purged`, terminal); purge is valid only on a `Deleted` post and is
 * idempotent under redelivery.
 */
final class PostPurgeSpec extends AnyWordSpec with Matchers:

  private val now = Instant.parse("2026-07-11T00:00:00Z")
  private val postId = PostId.unsafe("post|1")
  private val md5 = Md5("abc123")
  private val filetype = Filetype("image/png")
  private val dims = Dimensions.unsafe(800, 600, None)
  private val derivatives = Vector(Derivative("sample", "blob://sample"))
  private val phash = Phash("ph-1")

  private def active: PostState =
    PostDomain.replay(
      Seq(PostCreated(postId, md5, filetype, now), MediaProcessed(dims, derivatives, phash, now))
    )
  private def deleted: PostState = PostDomain.evolve(active, PostDeleted(now))

  "Purge" should {

    "permanently purge a soft-deleted post (Deleted → Purged)" in {
      val Right(events) = PostDomain.decide(deleted, Purge(now)): @unchecked
      events shouldBe Seq(PostPurged(now))
      PostDomain.evolve(deleted, events.head) shouldBe Purged(postId)
    }

    "reject Purge on an active post (must soft-delete first)" in {
      PostDomain.decide(active, Purge(now)) shouldBe Left(DomainError.PostNotFound)
    }

    "be idempotent — purging an already-purged post emits nothing" in {
      val purged = PostDomain.evolve(deleted, PostPurged(now))
      PostDomain.decide(purged, Purge(now)) shouldBe Right(Seq.empty)
    }

    "reject every non-purge command on a purged post (terminal)" in {
      val purged = PostDomain.evolve(deleted, PostPurged(now))
      PostDomain.decide(purged, Restore(now)) shouldBe Left(DomainError.PostNotFound)
      PostDomain.decide(purged, Delete(now)) shouldBe Left(DomainError.PostNotFound)
    }

    "keep a purged post gone across replay" in {
      val replayed = PostDomain.replay(
        Seq(
          PostCreated(postId, md5, filetype, now),
          MediaProcessed(dims, derivatives, phash, now),
          PostDeleted(now),
          PostPurged(now)
        )
      )
      replayed shouldBe Purged(postId)
    }
  }
