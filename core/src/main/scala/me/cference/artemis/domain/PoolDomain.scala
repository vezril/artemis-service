package me.cference.artemis.domain

import me.cference.artemis.domain.PoolCommand.*
import me.cference.artemis.domain.PoolEvent.*
import me.cference.artemis.domain.PoolState.*

/**
 * Pure state-transition logic for the pool aggregate. Two total functions:
 *
 *   - [[decide]] : `(State, Command) => Either[DomainError, Seq[Event]]` — validates a command
 *     against current state, producing events (or an error and zero events). No side effects, no
 *     clock reads.
 *   - [[evolve]] : `(State, Event) => State` — applies a fact to fold state.
 *
 * Invariants enforced: no operations on a non-existent pool; no duplicate creation; membership is
 * an ordered `Vector[PostId]`; adding a duplicate is an idempotent no-op; removing an absent post
 * is rejected; a reorder must be a permutation of current membership; `Deleted` is a tombstone.
 */
object PoolDomain:

  def decide(state: PoolState, command: PoolCommand): Either[DomainError, Seq[PoolEvent]] =
    state match
      case Empty =>
        command match
          case CreatePool(id, name, at) => Right(Seq(PoolCreated(id, name, at)))
          case _ => Left(DomainError.PoolNotFound)

      case Active(_, _, posts) =>
        command match
          case _: CreatePool =>
            Left(DomainError.PoolAlreadyExists)

          case AddPost(post, at) =>
            // Idempotent: adding a post already in the pool emits nothing.
            if posts.contains(post) then Right(Seq.empty) else Right(Seq(PostAdded(post, at)))

          case RemovePost(post, at) =>
            if posts.contains(post) then Right(Seq(PostRemoved(post, at)))
            else Left(DomainError.PostNotInPool)

          case Reorder(order, at) =>
            // The new order must be exactly the current membership, permuted.
            if order.toSet == posts.toSet && order.size == posts.size then
              Right(Seq(Reordered(order, at)))
            else Left(DomainError.PostNotInPool)

          case RenamePool(name, at) =>
            Right(Seq(PoolRenamed(name, at)))

          case DeletePool(at) =>
            Right(Seq(PoolDeleted(at)))

      case _: Deleted =>
        // Tombstone: no operations on a deleted pool, including recreation.
        Left(DomainError.PoolNotFound)

  def evolve(state: PoolState, event: PoolEvent): PoolState =
    (state, event) match
      case (Empty, PoolCreated(id, name, _)) =>
        Active(id, name, Vector.empty)

      case (Active(id, name, posts), PostAdded(post, _)) =>
        Active(id, name, posts :+ post)

      case (Active(id, name, posts), PostRemoved(post, _)) =>
        Active(id, name, posts.filterNot(_ == post))

      case (Active(id, name, _), Reordered(order, _)) =>
        Active(id, name, order)

      case (Active(id, _, posts), PoolRenamed(name, _)) =>
        Active(id, name, posts)

      case (Active(id, name, _), PoolDeleted(_)) =>
        Deleted(id, name)

      // No other (state, event) pair is producible by `decide`; keep total.
      case (current, _) => current

  /** Convenience for tests/replay: fold a full event list from the initial state. */
  def replay(events: Seq[PoolEvent]): PoolState =
    events.foldLeft(PoolState.initial)(evolve)
