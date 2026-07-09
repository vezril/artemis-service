package me.cference.artemis.domain

import java.time.Instant

/**
 * Facts that have happened to a post aggregate. Each event is self-contained: folding a state
 * purely from an event list requires no external lookup. These are the on-disk journal contract —
 * evolve additively only.
 */
sealed trait PostEvent extends CborSerializable:
  def at: Instant

object PostEvent:
  final case class PostCreated(id: PostId, md5: Md5, filetype: Filetype, at: Instant)
      extends PostEvent

  final case class MediaProcessed(
      dimensions: Dimensions,
      derivatives: Vector[Derivative],
      phash: Phash,
      at: Instant
  ) extends PostEvent

  /** Processing failed terminally for a pending post; `reason` is the human-readable cause. */
  final case class ProcessingFailed(reason: String, at: Instant) extends PostEvent

  final case class PostDeleted(at: Instant) extends PostEvent
  final case class PostRestored(at: Instant) extends PostEvent

  /**
   * The canonical tag set now in force. The ordered sequence of these events IS the post's tag-edit
   * history — each carries the full canonical set at that point, recoverable by replay with no
   * external lookup.
   */
  final case class TagsChanged(tags: Set[Tag], at: Instant) extends PostEvent

  final case class RatingChanged(rating: Rating, at: Instant) extends PostEvent
  final case class ParentSet(parent: PostId, at: Instant) extends PostEvent
  final case class Favorited(at: Instant) extends PostEvent
  final case class Unfavorited(at: Instant) extends PostEvent

  /**
   * The post's absolute score total now in force (not a delta). Carrying the total rather than the
   * increment keeps folding a pure assignment, so the read-model projection can upsert it
   * idempotently under at-least-once redelivery. The `Score(delta)` command still expresses "+n";
   * the decider adds it to the current total before emitting this event.
   */
  final case class Scored(score: Int, at: Instant) extends PostEvent
  final case class SourceChanged(source: String, at: Instant) extends PostEvent

  /**
   * Post-processing found this post's phash within the near-duplicate threshold of an existing
   * post. The flag is a domain event (not a read-side side-write) so the read model stays
   * rebuildable by replay. `matchedPostId` is the existing post it resembles.
   */
  final case class PossibleDuplicateFlagged(matchedPostId: PostId, at: Instant) extends PostEvent
