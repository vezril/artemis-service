package me.cference.artemis.search

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.util.Try

/**
 * An opaque keyset-pagination cursor (search-dsl "Ordering with keyset pagination"). Carries the
 * last row's ordering position so the next page is a row-value `WHERE`, never `OFFSET`:
 *
 *   - `order` — the normalized order name the page was produced with (`score`, `id`, `random`…).
 *   - `lastId` — the last row's id, the deterministic tiebreak.
 *   - `lastKey` — the last row's order-column value as a string (re-parsed to the column type on
 *     the next page). `None` for `id`/`random`, whose keyset needs only the id (and seed).
 *   - `seed` — the `order:random` seed, carried across pages so the shuffle is stable (no
 *     reshuffle, no repeat/skip).
 *
 * Encoded as `.`-joined URL-safe base64 of each field so it is a single opaque token; the `.`
 * delimiter is safe because URL-safe base64 never emits `.`.
 */
final case class Cursor(
    order: String,
    lastId: String,
    lastKey: Option[String],
    seed: Option[String]
):
  def encode: String =
    Seq(order, lastId, lastKey.getOrElse(""), seed.getOrElse(""))
      .map(f => Base64.getUrlEncoder.withoutPadding.encodeToString(f.getBytes(UTF_8)))
      .mkString(".")

object Cursor:

  def decode(s: String): Either[CompileError, Cursor] =
    s.split("\\.", -1).toList match
      case List(o, i, k, sd) =>
        Try {
          def dec(field: String): String = new String(Base64.getUrlDecoder.decode(field), UTF_8)
          Cursor(
            order = dec(o),
            lastId = dec(i),
            lastKey = Option(dec(k)).filter(_.nonEmpty),
            seed = Option(dec(sd)).filter(_.nonEmpty)
          )
        }.toEither.left.map(e =>
          CompileError.InvalidCursor(s"undecodable cursor (${e.getMessage})")
        )
      case _ =>
        Left(CompileError.InvalidCursor("expected four base64 segments"))
