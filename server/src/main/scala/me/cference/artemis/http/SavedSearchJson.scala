package me.cference.artemis.http

import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/** `POST /saved-searches` body: name + the raw DSL query to store. */
final case class SaveSearchRequest(name: String, query: String)

/** `PATCH /saved-searches/{name}` body: the new name to rename to. */
final case class RenameSearchRequest(name: String)

/** One entry in the saved-search list. */
final case class SavedSearchItem(name: String, query: String)

/** `GET /saved-searches` response: the ordered list. */
final case class SavedSearchListResponse(searches: List[SavedSearchItem])

object SavedSearchJson:
  given RootJsonFormat[SaveSearchRequest] = jsonFormat2(SaveSearchRequest.apply)
  given RootJsonFormat[RenameSearchRequest] = jsonFormat1(RenameSearchRequest.apply)
  given RootJsonFormat[SavedSearchItem] = jsonFormat2(SavedSearchItem.apply)
  given RootJsonFormat[SavedSearchListResponse] = jsonFormat1(SavedSearchListResponse.apply)
