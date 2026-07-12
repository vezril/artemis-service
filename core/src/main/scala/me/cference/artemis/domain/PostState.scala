package me.cference.artemis.domain

/**
 * The folded state of one post aggregate. A journal for a given post moves `Empty -> Pending ->
 * Active -> Deleted`; a `Deleted` post is either restored back to `Active` (`Restore`) or, after
 * its retention window, permanently `Purged` (`Purge`) — a terminal state that rejects every
 * command. `Pending` is a created-but-not-yet-processed post; `RecordProcessed` promotes it to
 * `Active`, carrying the derived media facts and the mutable content (tags, rating, relationships,
 * favorites, score, source) that later commands edit. A pending post whose processing fails
 * (`MarkFailed`) folds to `Failed`, a terminal state that rejects every command.
 */
enum PostState:
  case Empty
  case Pending(id: PostId, md5: Md5, filetype: Filetype)
  case Active(id: PostId, media: PostMedia, content: PostContent)
  case Deleted(id: PostId, media: PostMedia, content: PostContent)
  case Failed(id: PostId, md5: Md5, filetype: Filetype, reason: String)
  case Purged(id: PostId)

object PostState:
  val initial: PostState = Empty

/** Immutable snapshot of the media facts produced by processing. */
final case class PostMedia(
    md5: Md5,
    filetype: Filetype,
    dimensions: Dimensions,
    derivatives: Vector[Derivative],
    phash: Phash,
    derivativeSpecVersion: Int = 0
)

/**
 * The editable, event-sourced content of an active post: canonical tag set, rating, parent,
 * favorite flag, score, and source. Copy-on-write via `.copy` as events fold in.
 *
 * `suggestions` + `needsReview` are the auto-tagging state: Argus's canonical tag suggestions
 * (distinct from `tags`, the APPLIED set) held pending a human review, and the flag that puts the
 * post in the review queue. Recording suggestions raises the flag; accepting (which applies chosen
 * suggestions as a normal tag edit) or rejecting clears it and empties the pending set.
 */
final case class PostContent(
    tags: Set[Tag] = Set.empty,
    rating: Option[Rating] = None,
    parent: Option[PostId] = None,
    favorited: Boolean = false,
    score: Int = 0,
    source: Option[String] = None,
    duplicateOf: Option[PostId] = None,
    suggestions: Vector[SuggestedTag] = Vector.empty,
    needsReview: Boolean = false,
    taggerVersion: Int = 0
)

object PostContent:
  val empty: PostContent = PostContent()
