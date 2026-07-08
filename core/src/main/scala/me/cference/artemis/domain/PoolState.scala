package me.cference.artemis.domain

/**
 * The folded state of one pool aggregate. A journal for a given pool moves `Empty -> Active ->
 * Deleted`; `Deleted` is a terminal tombstone. An `Active` pool holds an ordered `Vector[PostId]` —
 * membership order is meaningful and preserved across add/remove/reorder.
 */
enum PoolState:
  case Empty
  case Active(id: PoolId, name: PoolName, posts: Vector[PostId])
  case Deleted(id: PoolId, name: PoolName)

object PoolState:
  val initial: PoolState = Empty
