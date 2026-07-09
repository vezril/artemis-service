package me.cference.artemis.http

import me.cference.artemis.domain.PostState

/**
 * Renders a folded `PostState` into the flat `PostResponse` DTO served by `GET /posts/{id}`. A
 * `Pending` post has no processed media yet, so its media/content fields are absent; an `Active`
 * (or `Deleted`) post carries the full media facts and editable content.
 */
object PostJson:

  def render(id: String, state: PostState): PostResponse =
    state match
      case PostState.Empty =>
        // Caller maps Empty to 404 before reaching here; render defensively as an empty shell.
        PostResponse(
          id,
          "empty",
          Nil,
          None,
          0,
          favorited = false,
          None,
          None,
          None,
          None,
          None,
          None,
          None
        )

      case PostState.Pending(pid, md5, filetype) =>
        PostResponse(
          id = pid.value,
          status = "pending",
          tags = Nil,
          rating = None,
          score = 0,
          favorited = false,
          parent = None,
          source = None,
          md5 = Some(md5.value),
          filetype = Some(filetype.value),
          width = None,
          height = None,
          duration = None
        )

      case PostState.Failed(pid, md5, filetype, _) =>
        // Terminal processing failure: no media facts, surfaced as status "failed".
        PostResponse(
          id = pid.value,
          status = "failed",
          tags = Nil,
          rating = None,
          score = 0,
          favorited = false,
          parent = None,
          source = None,
          md5 = Some(md5.value),
          filetype = Some(filetype.value),
          width = None,
          height = None,
          duration = None
        )

      case PostState.Active(pid, media, content) =>
        active(pid.value, "active", media, content)

      case PostState.Deleted(pid, media, content) =>
        active(pid.value, "deleted", media, content)

  private def active(
      id: String,
      status: String,
      media: me.cference.artemis.domain.PostMedia,
      content: me.cference.artemis.domain.PostContent
  ): PostResponse =
    PostResponse(
      id = id,
      status = status,
      tags = content.tags.map(_.value).toList.sorted,
      rating = content.rating.map(_.code),
      score = content.score,
      favorited = content.favorited,
      parent = content.parent.map(_.value),
      source = content.source,
      md5 = Some(media.md5.value),
      filetype = Some(media.filetype.value),
      width = Some(media.dimensions.width),
      height = Some(media.dimensions.height),
      duration = media.dimensions.duration
    )
