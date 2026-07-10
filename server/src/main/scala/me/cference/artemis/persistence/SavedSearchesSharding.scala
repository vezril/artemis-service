package me.cference.artemis.persistence

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  Entity,
  EntityRef,
  EntityTypeKey
}

/**
 * Cluster Sharding for the saved-searches aggregate (saved-searches spec). Single-user = one list,
 * so there is a single hosted entity addressed by [[SavedSearchesEntity.SingletonId]] — still
 * routed through sharding for single-writer safety and consistency with the Post/Pool entities.
 */
object SavedSearchesSharding:

  val TypeKey: EntityTypeKey[SavedSearchesEntity.Command] =
    EntityTypeKey[SavedSearchesEntity.Command](SavedSearchesEntity.EntityPrefix)

  /** Initialize sharding on the cluster and return the handle. */
  def init(system: ActorSystem[?]): ClusterSharding =
    val sharding = ClusterSharding(system)
    val _ = sharding.init(Entity(TypeKey)(ctx => SavedSearchesEntity(ctx.entityId)))
    sharding

  /** The one list's entity ref (fixed singleton id). */
  def entityRef(sharding: ClusterSharding): EntityRef[SavedSearchesEntity.Command] =
    sharding.entityRefFor(TypeKey, SavedSearchesEntity.SingletonId)
