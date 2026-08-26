package me.cference.artemis.http

import spray.json.*
import spray.json.DefaultJsonProtocol.*

/**
 * Hand-shaped request/response DTOs for the Catalog HTTP API and their spray-json formats. Domain
 * value types are mapped to plain strings/ints at this boundary — the routes lift them back into
 * validated domain types (or `unsafe` for ids addressed by URL). Kept deliberately small and flat,
 * mirroring Apollo's HealthRoutes body shape.
 */

// --- Post requests ---

final case class CreatePostRequest(id: String, md5: String, filetype: String)
final case class ChangeTagsRequest(tags: List[String])
final case class ScoreRequest(delta: Int)
final case class SetRatingRequest(rating: String)

// --- Post responses ---

final case class CreatePostResponse(postId: String, status: String)

final case class PostResponse(
    id: String,
    status: String,
    tags: List[String],
    rating: Option[String],
    score: Int,
    favorited: Boolean,
    parent: Option[String],
    source: Option[String],
    md5: Option[String],
    filetype: Option[String],
    width: Option[Int],
    height: Option[Int],
    duration: Option[Long],
    derivatives: List[DerivativeRef]
)

// --- Pool requests ---

final case class CreatePoolRequest(id: String, name: String)
final case class AddPostRequest(postId: String)
final case class ReorderRequest(order: List[String])
final case class RenamePoolRequest(name: String)

// --- Pool responses ---

final case class CreatePoolResponse(poolId: String, status: String)
final case class PoolResponse(id: String, name: String, posts: List[String])

// --- Upload ---

final case class UploadResponse(postId: String, status: String)

// --- Errors ---

final case class ErrorResponse(error: String)

object CatalogJson:
  given RootJsonFormat[CreatePostRequest] = jsonFormat3(CreatePostRequest.apply)
  given RootJsonFormat[ChangeTagsRequest] = jsonFormat1(ChangeTagsRequest.apply)
  given RootJsonFormat[ScoreRequest] = jsonFormat1(ScoreRequest.apply)
  given RootJsonFormat[SetRatingRequest] = jsonFormat1(SetRatingRequest.apply)
  given RootJsonFormat[CreatePostResponse] = jsonFormat2(CreatePostResponse.apply)
  given RootJsonFormat[PostResponse] = jsonFormat14(PostResponse.apply)

  given RootJsonFormat[CreatePoolRequest] = jsonFormat2(CreatePoolRequest.apply)
  given RootJsonFormat[AddPostRequest] = jsonFormat1(AddPostRequest.apply)
  given RootJsonFormat[ReorderRequest] = jsonFormat1(ReorderRequest.apply)
  given RootJsonFormat[RenamePoolRequest] = jsonFormat1(RenamePoolRequest.apply)
  given RootJsonFormat[CreatePoolResponse] = jsonFormat2(CreatePoolResponse.apply)
  given RootJsonFormat[PoolResponse] = jsonFormat3(PoolResponse.apply)

  given RootJsonFormat[UploadResponse] = jsonFormat2(UploadResponse.apply)

  given RootJsonFormat[ErrorResponse] = jsonFormat1(ErrorResponse.apply)
