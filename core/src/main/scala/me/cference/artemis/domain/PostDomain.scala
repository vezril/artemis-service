package me.cference.artemis.domain

import me.cference.artemis.domain.PostState.*
import me.cference.artemis.domain.PostCommand.*
import me.cference.artemis.domain.PostEvent.*

/**
 * Pure state-transition logic for the post aggregate. Two total functions:
 *
 *   - [[decide]] : `(State, Command) => Either[DomainError, Seq[Event]]` — validates a command
 *     against current state, producing events (or an error and zero events). No side effects, no
 *     clock reads.
 *   - [[evolve]] : `(State, Event) => State` — applies a fact to fold state.
 *
 * Invariants enforced: no operations on a non-existent post; no duplicate creation; a `Deleted`
 * post is a tombstone that rejects every command except `Restore`.
 */
object PostDomain:

  def decide(
      state: PostState,
      command: PostCommand,
      tagGraph: TagGraph = TagGraph.empty
  ): Either[DomainError, Seq[PostEvent]] =
    state match
      case Empty =>
        command match
          case CreatePost(id, md5, filetype, at) => Right(Seq(PostCreated(id, md5, filetype, at)))
          case _ => Left(DomainError.PostNotFound)

      case _: Pending =>
        command match
          case _: CreatePost => Left(DomainError.PostAlreadyExists)
          case RecordProcessed(dimensions, derivatives, phash, at) =>
            Right(Seq(MediaProcessed(dimensions, derivatives, phash, at)))
          case Restore(_) => Right(Seq.empty) // already live: no-op
          // A pending post has no processed media, so the media/content-carrying `Deleted`
          // tombstone can't represent it — and content edits need processed media anyway.
          // Reject `Delete` and every content edit here rather than emit an event `evolve`
          // can't fold (which would leave a phantom-deleted post on replay).
          case _ => Left(DomainError.PostNotFound)

      case Active(_, _, content) =>
        command match
          case _: CreatePost =>
            Left(DomainError.PostAlreadyExists)

          case RecordProcessed(dimensions, derivatives, phash, at) =>
            Right(Seq(MediaProcessed(dimensions, derivatives, phash, at)))

          case Delete(at) =>
            Right(Seq(PostDeleted(at)))

          case Restore(_) =>
            // Already live: restoring a non-deleted post is an accepted no-op.
            Right(Seq.empty)

          case ChangeTags(tags, at) =>
            Right(Seq(TagsChanged(TagCanonicalization.canonicalize(tags, tagGraph), at)))

          case SetRating(rating, at) =>
            Rating.from(rating).map(r => Seq(RatingChanged(r, at)))

          case SetParent(parent, at) =>
            Right(Seq(ParentSet(parent, at)))

          case Favorite(at) =>
            // Idempotent: re-favoriting an already-favorited post emits nothing.
            if content.favorited then Right(Seq.empty) else Right(Seq(Favorited(at)))

          case Unfavorite(at) =>
            // Idempotent: unfavoriting a non-favorited post emits nothing.
            if content.favorited then Right(Seq(Unfavorited(at))) else Right(Seq.empty)

          case Score(delta, at) =>
            // The command is a delta ("+n"); the event carries the resulting absolute total so the
            // read-model projection folds it as a pure upsert (idempotent under redelivery).
            Right(Seq(Scored(content.score + delta, at)))

          case SetSource(source, at) =>
            Right(Seq(SourceChanged(source, at)))

      case _: Deleted =>
        command match
          case Restore(at) => Right(Seq(PostRestored(at)))
          // Tombstone: every other command on a deleted post is rejected.
          case _ => Left(DomainError.PostNotFound)

  def evolve(state: PostState, event: PostEvent): PostState =
    (state, event) match
      case (Empty, PostCreated(id, md5, filetype, _)) =>
        Pending(id, md5, filetype)

      case (Pending(id, md5, filetype), MediaProcessed(dimensions, derivatives, phash, _)) =>
        Active(id, PostMedia(md5, filetype, dimensions, derivatives, phash), PostContent.empty)

      case (Active(id, media, content), MediaProcessed(dimensions, derivatives, phash, _)) =>
        // Reprocessing an already-active post refreshes its media facts, keeps its content.
        Active(
          id,
          media.copy(dimensions = dimensions, derivatives = derivatives, phash = phash),
          content
        )

      case (Active(id, media, content), PostDeleted(_)) =>
        Deleted(id, media, content)

      case (Deleted(id, media, content), PostRestored(_)) =>
        Active(id, media, content)

      case (Active(id, media, content), TagsChanged(tags, _)) =>
        Active(id, media, content.copy(tags = tags))

      case (Active(id, media, content), RatingChanged(rating, _)) =>
        Active(id, media, content.copy(rating = Some(rating)))

      case (Active(id, media, content), ParentSet(parent, _)) =>
        Active(id, media, content.copy(parent = Some(parent)))

      case (Active(id, media, content), Favorited(_)) =>
        Active(id, media, content.copy(favorited = true))

      case (Active(id, media, content), Unfavorited(_)) =>
        Active(id, media, content.copy(favorited = false))

      case (Active(id, media, content), Scored(score, _)) =>
        Active(id, media, content.copy(score = score))

      case (Active(id, media, content), SourceChanged(source, _)) =>
        Active(id, media, content.copy(source = Some(source)))

      // No other (state, event) pair is producible by `decide`; keep total.
      case (current, _) => current

  /** Convenience for tests/replay: fold a full event list from the initial state. */
  def replay(events: Seq[PostEvent]): PostState =
    events.foldLeft(PostState.initial)(evolve)
