package me.cference.artemis.persistence

import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}
import me.cference.artemis.domain.SavedSearchesEvent

/**
 * Jackson mix-in that adds polymorphic type information to the pure `SavedSearchesEvent` ADT
 * without putting annotations in `core`. Applied via `setMixInAnnotations` in
 * [[DomainJacksonModule]]. The `_type` names are the stable on-disk schema contract — never rename
 * them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "_type")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[SavedSearchesEvent.SearchSaved], name = "SearchSaved"),
    new JsonSubTypes.Type(
      value = classOf[SavedSearchesEvent.SearchRenamed],
      name = "SearchRenamed"
    ),
    new JsonSubTypes.Type(value = classOf[SavedSearchesEvent.SearchRemoved], name = "SearchRemoved")
  )
)
private[persistence] trait SavedSearchesEventMixin
