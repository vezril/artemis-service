package me.cference.artemis.persistence

import me.cference.artemis.domain.{PostId, TagGraph}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.{
  ClusterSharding,
  Entity,
  EntityRef,
  EntityTypeKey
}

/**
 * Cluster Sharding for post entities (service-runtime spec): each post is a cluster-wide singleton
 * addressed by `EntityRef`, so at most one writer exists per id across the cluster. The entity type
 * key name is `post`, so the sharded persistence id stays `post|<id>` — the frozen contract that
 * [[PostEntity.persistenceId]] and the read-model projection (`EntityType = "post"`) already
 * assume, with no change to `PostEntity`.
 *
 * The tag-graph supplier is threaded through `init` into every hosted entity so canonicalization
 * reads the periodically-refreshed [[me.cference.artemis.tags.TagGraphCache]] snapshot (read per
 * command, cheap map lookup) rather than a per-command DB load.
 */
object PostSharding:

  val TypeKey: EntityTypeKey[PostEntity.Command] =
    EntityTypeKey[PostEntity.Command](PostEntity.EntityPrefix)

  /** Initialize sharding on the cluster and return the handle. */
  def init(
      system: ActorSystem[?],
      tagGraph: () => TagGraph = () => TagGraph.empty
  ): ClusterSharding =
    val sharding = ClusterSharding(system)
    sharding.init(Entity(TypeKey)(ctx => PostEntity(PostId.unsafe(ctx.entityId), tagGraph)))
    sharding

  def entityRef(sharding: ClusterSharding, id: String): EntityRef[PostEntity.Command] =
    sharding.entityRefFor(TypeKey, id)
