package me.cference.artemis.http

import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/** One pending suggestion shown for review: the canonical tag, its confidence, and the model. */
final case class SuggestionItem(tag: String, confidence: Double, source: String)

/** One post in the review queue: its id and the pending suggestions (highest confidence first). */
final case class ReviewPostItem(postId: String, suggestions: List[SuggestionItem])

/** `GET /review` response: the auto-tag review backlog, oldest post first. */
final case class ReviewQueueResponse(posts: List[ReviewPostItem])

/**
 * `POST /posts/{id}/review` body. `accept` is the tags the reviewer chose to apply (the suggested
 * ones, edited, or freely added); absent or empty means reject-all — clear review, apply nothing.
 */
final case class ReviewRequest(accept: Option[List[String]])

object ReviewJson:
  given RootJsonFormat[SuggestionItem] = jsonFormat3(SuggestionItem.apply)
  given RootJsonFormat[ReviewPostItem] = jsonFormat2(ReviewPostItem.apply)
  given RootJsonFormat[ReviewQueueResponse] = jsonFormat1(ReviewQueueResponse.apply)
  given RootJsonFormat[ReviewRequest] = jsonFormat1(ReviewRequest.apply)
