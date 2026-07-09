package me.cference.artemis.domain

/**
 * The folded state of one post aggregate. A journal for a given post moves `Empty -> Pending ->
 * Active -> Deleted`; `Deleted` is a terminal tombstone (only `Restore` can leave it). `Pending` is
 * a created-but-not-yet-processed post; `RecordProcessed` promotes it to `Active`, carrying the
 * derived media facts and the mutable content (tags, rating, relationships, favorites, score,
 * source) that later commands edit.
 */
enum PostState:
  case Empty
  case Pending(id: PostId, md5: Md5, filetype: Filetype)
  case Active(id: PostId, media: PostMedia, content: PostContent)
  case Deleted(id: PostId, media: PostMedia, content: PostContent)

object PostState:
  val initial: PostState = Empty

/** Immutable snapshot of the media facts produced by processing. */
final case class PostMedia(
    md5: Md5,
    filetype: Filetype,
    dimensions: Dimensions,
    derivatives: Vector[Derivative],
    phash: Phash
)

/**
 * The editable, event-sourced content of an active post: canonical tag set, rating, parent,
 * favorite flag, score, and source. Copy-on-write via `.copy` as events fold in.
 */
final case class PostContent(
    tags: Set[Tag] = Set.empty,
    rating: Option[Rating] = None,
    parent: Option[PostId] = None,
    favorited: Boolean = false,
    score: Int = 0,
    source: Option[String] = None
)

object PostContent:
  val empty: PostContent = PostContent()
