package me.cference.artemis.persistence

import me.cference.artemis.domain.PostEvent
import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}

/**
 * Jackson mix-in that adds polymorphic type information to the pure `PostEvent` ADT without putting
 * annotations in `core`. Applied via `setMixInAnnotations` in [[DomainJacksonModule]]. The `_type`
 * names are the stable on-disk schema contract — never rename them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "_type")
@JsonSubTypes(
  Array(
    new JsonSubTypes.Type(value = classOf[PostEvent.PostCreated], name = "PostCreated"),
    new JsonSubTypes.Type(value = classOf[PostEvent.MediaProcessed], name = "MediaProcessed"),
    new JsonSubTypes.Type(value = classOf[PostEvent.ProcessingFailed], name = "ProcessingFailed"),
    new JsonSubTypes.Type(value = classOf[PostEvent.PostDeleted], name = "PostDeleted"),
    new JsonSubTypes.Type(value = classOf[PostEvent.PostRestored], name = "PostRestored"),
    new JsonSubTypes.Type(value = classOf[PostEvent.TagsChanged], name = "TagsChanged"),
    new JsonSubTypes.Type(value = classOf[PostEvent.RatingChanged], name = "RatingChanged"),
    new JsonSubTypes.Type(value = classOf[PostEvent.ParentSet], name = "ParentSet"),
    new JsonSubTypes.Type(value = classOf[PostEvent.Favorited], name = "Favorited"),
    new JsonSubTypes.Type(value = classOf[PostEvent.Unfavorited], name = "Unfavorited"),
    new JsonSubTypes.Type(value = classOf[PostEvent.Scored], name = "Scored"),
    new JsonSubTypes.Type(value = classOf[PostEvent.SourceChanged], name = "SourceChanged")
  )
)
private[persistence] trait PostEventMixin
