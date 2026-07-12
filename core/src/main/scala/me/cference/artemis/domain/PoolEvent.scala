package me.cference.artemis.domain

import java.time.Instant

/**
 * Facts that have happened to a pool aggregate, kept entirely separate from the post aggregate's
 * events. Each event is self-contained: folding a state purely from an event list requires no
 * external lookup. These are the on-disk journal contract — evolve additively only.
 */
sealed trait PoolEvent extends CborSerializable:
  def at: Instant

object PoolEvent:
  final case class PoolCreated(id: PoolId, name: PoolName, at: Instant) extends PoolEvent
  final case class PostAdded(post: PostId, at: Instant) extends PoolEvent
  final case class PostRemoved(post: PostId, at: Instant) extends PoolEvent
  final case class Reordered(order: Vector[PostId], at: Instant) extends PoolEvent
  final case class PoolRenamed(name: PoolName, at: Instant) extends PoolEvent
  final case class PoolDeleted(at: Instant) extends PoolEvent
