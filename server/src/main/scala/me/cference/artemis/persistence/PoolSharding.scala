package me.cference.artemis.persistence

import me.cference.artemis.domain.PoolId
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  Entity,
  EntityRef,
  EntityTypeKey
}

/**
 * Cluster Sharding for pool entities (service-runtime spec): each pool is a cluster-wide singleton
 * addressed by `EntityRef`, so at most one writer exists per id across the cluster. The entity type
 * key name is `pool`, so the sharded persistence id stays `pool|<id>` — the frozen contract that
 * [[PoolEntity.persistenceId]] and the read-model projection (`EntityType = "pool"`) already
 * assume, with no change to `PoolEntity`.
 */
object PoolSharding:

  val TypeKey: EntityTypeKey[PoolEntity.Command] =
    EntityTypeKey[PoolEntity.Command](PoolEntity.EntityPrefix)

  /** Initialize sharding on the cluster and return the handle. */
  def init(system: ActorSystem[?]): ClusterSharding =
    val sharding = ClusterSharding(system)
    val _ = sharding.init(Entity(TypeKey)(ctx => PoolEntity(PoolId.unsafe(ctx.entityId))))
    sharding

  def entityRef(sharding: ClusterSharding, id: String): EntityRef[PoolEntity.Command] =
    sharding.entityRefFor(TypeKey, id)
