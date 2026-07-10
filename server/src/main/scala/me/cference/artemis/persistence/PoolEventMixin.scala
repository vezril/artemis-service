package me.cference.artemis.persistence

import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}
import me.cference.artemis.domain.PoolEvent

/**
 * Jackson mix-in that adds polymorphic type information to the pure `PoolEvent` ADT without putting
 * annotations in `core`. Applied via `setMixInAnnotations` in [[DomainJacksonModule]]. The `_type`
 * names are the stable on-disk schema contract — never rename them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "_type")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[PoolEvent.PoolCreated], name = "PoolCreated"),
    new JsonSubTypes.Type(value = classOf[PoolEvent.PostAdded], name = "PostAdded"),
    new JsonSubTypes.Type(value = classOf[PoolEvent.PostRemoved], name = "PostRemoved"),
    new JsonSubTypes.Type(value = classOf[PoolEvent.Reordered], name = "Reordered"),
    new JsonSubTypes.Type(value = classOf[PoolEvent.PoolRenamed], name = "PoolRenamed"),
    new JsonSubTypes.Type(value = classOf[PoolEvent.PoolDeleted], name = "PoolDeleted")
  )
)
private[persistence] trait PoolEventMixin
