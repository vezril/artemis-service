package me.cference.artemis.http

import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/** One related tag: the co-occurring name, how many posts carry both, and the cosine score. */
final case class RelatedTagItem(name: String, cooccurrence: Int, cosine: Double)

/** `GET /tags/{name}/related` response: cosine-ranked related tags. */
final case class RelatedTagsResponse(related: List[RelatedTagItem])

object RelatedTagsJson:
  given RootJsonFormat[RelatedTagItem] = jsonFormat3(RelatedTagItem.apply)
  given RootJsonFormat[RelatedTagsResponse] = jsonFormat1(RelatedTagsResponse.apply)
