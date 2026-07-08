package me.cference.artemis.domain

import java.time.Instant

/**
 * Commands directed at a single pool aggregate. Kept entirely separate from the post aggregate's
 * commands. `at` is the caller-supplied instant; the pure decider stamps produced events with it,
 * keeping transitions deterministic (no hidden clock reads).
 */
sealed trait PoolCommand:
  def at: Instant

object PoolCommand:
  final case class CreatePool(id: PoolId, name: PoolName, at: Instant) extends PoolCommand

  /** Append a post. Idempotent: adding a post already in the pool is an accepted no-op. */
  final case class AddPost(post: PostId, at: Instant) extends PoolCommand

  /** Remove a post. Removing a post that is not a member is rejected. */
  final case class RemovePost(post: PostId, at: Instant) extends PoolCommand

  /** Reorder membership. The new order must be a permutation of current membership. */
  final case class Reorder(order: Vector[PostId], at: Instant) extends PoolCommand

  final case class RenamePool(name: PoolName, at: Instant) extends PoolCommand
  final case class DeletePool(at: Instant) extends PoolCommand
