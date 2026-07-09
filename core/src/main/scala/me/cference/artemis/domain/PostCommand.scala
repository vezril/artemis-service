package me.cference.artemis.domain

import java.time.Instant

/**
 * Commands directed at a single post aggregate. Every command accepts only validated value types
 * (`PostId`, `Tag`, `Rating`, …) — raw unvalidated `String`s cannot enter the aggregate.
 *
 * `at` is the caller-supplied instant the command was issued; the pure decider stamps produced
 * events with it, keeping transitions deterministic (no hidden clock reads). The effectful caller
 * (e.g. the HTTP routes or the ingest services) fills it with `Instant.now()` when issuing the
 * command; the entity passes commands through unchanged.
 */
sealed trait PostCommand:
  def at: Instant

object PostCommand:
  final case class CreatePost(id: PostId, md5: Md5, filetype: Filetype, at: Instant)
      extends PostCommand

  final case class RecordProcessed(
      dimensions: Dimensions,
      derivatives: Vector[Derivative],
      phash: Phash,
      at: Instant
  ) extends PostCommand

  /**
   * Fail a pending post's processing (Hephaestus reported a `MediaFailed`). Only a `Pending` post
   * can transition to `Failed`; the decider rejects this on any other state.
   */
  final case class MarkFailed(reason: String, at: Instant) extends PostCommand

  final case class Delete(at: Instant) extends PostCommand
  final case class Restore(at: Instant) extends PostCommand

  /**
   * Set the post's tag set to `tags`. The decider canonicalizes this request (alias rewrite →
   * transitive implication expansion → dedup) against the supplied [[TagGraph]] before emitting
   * `TagsChanged`, so only the canonical set is journaled.
   */
  final case class ChangeTags(tags: Set[Tag], at: Instant) extends PostCommand

  /**
   * Set the content rating from a raw code. The decider validates it via [[Rating.from]]; a value
   * outside {g,s,q,e} is a typed rejection with no event.
   */
  final case class SetRating(rating: String, at: Instant) extends PostCommand

  final case class SetParent(parent: PostId, at: Instant) extends PostCommand

  /**
   * Favorite the post. Idempotent: re-favoriting an already-favorited post is an accepted no-op.
   */
  final case class Favorite(at: Instant) extends PostCommand

  /** Remove a favorite. Idempotent: unfavoriting a non-favorited post is an accepted no-op. */
  final case class Unfavorite(at: Instant) extends PostCommand

  /** Apply a score delta (e.g. +1 upvote, -1 downvote) to the running total. */
  final case class Score(delta: Int, at: Instant) extends PostCommand

  final case class SetSource(source: String, at: Instant) extends PostCommand
