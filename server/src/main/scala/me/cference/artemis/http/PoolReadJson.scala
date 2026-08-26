package me.cference.artemis.http

import spray.json.*
import spray.json.DefaultJsonProtocol.*

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.util.Try

/**
 * DTOs + spray-json formats for the pool READ surface (`GET /pools`, `GET /pools/{id}/posts`), plus
 * the two opaque keyset cursors those endpoints page on. Kept separate from [[PoolJson]] (which
 * renders the entity-backed, read-your-writes `GET /pools/{id}` as a bare id list) because these
 * reads are projection-backed and hydrated.
 *
 * The list reuses [[SearchJson.PostSummary]] for a pool's cover so the UI has one thumbnail-render
 * path; `GET /pools/{id}/posts` reuses [[SearchJson.SearchResponse]] wholesale (hydrated members in
 * pool order, same `{posts, nextCursor}` envelope as `GET /posts`).
 */

/** One pool card: id, name, member count over the VISIBLE (non-deleted) set, and a cover. */
final case class PoolSummary(id: String, name: String, postCount: Int, cover: Option[PostSummary])

/** A keyset page of pools. */
final case class PoolListResponse(pools: List[PoolSummary], nextCursor: Option[String])

/**
 * The list cursor: the last returned pool's `(lower(name), id)` — the exact keyset the page ordered
 * on (`lower(name) ASC, id ASC`). Encoded field-safely as `.`-joined URL-safe base64 of each field
 * (matching [[me.cference.artemis.search.Cursor]]'s style): base64 never emits `.`, so a pool name
 * with spaces/colons round-trips intact.
 */
final case class PoolListCursor(lowerName: String, id: String):
  def encode: String = PoolReadCursor.join(lowerName, id)

object PoolListCursor:
  def decode(s: String): Either[String, PoolListCursor] =
    PoolReadCursor.split2(s).map((n, i) => PoolListCursor(n, i))

/**
 * The members cursor: the last returned member's `(position, post_id)` — the composite keyset that
 * stays a total, unique order even when `position` has legacy duplicates/gaps. `position` is
 * encoded as its decimal string.
 */
final case class PoolPostsCursor(position: Int, postId: String):
  def encode: String = PoolReadCursor.join(position.toString, postId)

object PoolPostsCursor:
  def decode(s: String): Either[String, PoolPostsCursor] =
    PoolReadCursor.split2(s).flatMap { (p, pid) =>
      Try(p.toInt).toEither.left
        .map(_ => "cursor position is not an integer")
        .map(pos => PoolPostsCursor(pos, pid))
    }

/** Shared `.`-joined URL-safe base64 codec for the two pool cursors. */
private object PoolReadCursor:
  private val enc = Base64.getUrlEncoder.withoutPadding
  private val dec = Base64.getUrlDecoder

  def join(a: String, b: String): String =
    s"${enc.encodeToString(a.getBytes(UTF_8))}.${enc.encodeToString(b.getBytes(UTF_8))}"

  def split2(s: String): Either[String, (String, String)] =
    s.split("\\.", -1).toList match
      case List(a, b) =>
        Try((new String(dec.decode(a), UTF_8), new String(dec.decode(b), UTF_8))).toEither.left
          .map(e => s"undecodable cursor (${e.getMessage})")
      case _ => Left("expected two base64 segments")

object PoolReadJson:
  import SearchJson.given

  given RootJsonFormat[PoolSummary] = jsonFormat4(PoolSummary.apply)
  given RootJsonFormat[PoolListResponse] = jsonFormat2(PoolListResponse.apply)
