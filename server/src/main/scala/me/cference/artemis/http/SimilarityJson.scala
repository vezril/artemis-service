package me.cference.artemis.http

import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

/** One near-duplicate match: the post id and its perceptual-hash Hamming distance. */
final case class SimilarPostItem(id: String, distance: Int)

/** `GET /posts/{id}/similar` and `GET /similar` response: near-duplicates, closest first. */
final case class SimilarPostsResponse(similar: List[SimilarPostItem])

object SimilarityJson:
  given RootJsonFormat[SimilarPostItem] = jsonFormat2(SimilarPostItem.apply)
  given RootJsonFormat[SimilarPostsResponse] = jsonFormat1(SimilarPostsResponse.apply)
