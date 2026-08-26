package me.cference.artemis.http

import me.cference.artemis.domain.Derivative
import spray.json.*
import spray.json.DefaultJsonProtocol.*

/**
 * A media derivative reference exposed on the read API: `kind` (e.g. `thumbnail`, `sample`) and
 * `variant`, the media-gateway filename — the LAST `/`-segment of the stored `<bucket>/<object>`
 * derivative ref. A client builds the media URL as `<base>/media/<md5>/<variant>`, so the raw
 * Apollo object key never leaves the service. Shared by the search summary ([[PostSummary]]) and
 * the single-post response ([[PostResponse]]); its given format lives in the companion so both
 * formats pick it up without an extra import.
 */
final case class DerivativeRef(kind: String, variant: String)

object DerivativeRef:
  given RootJsonFormat[DerivativeRef] = jsonFormat2(DerivativeRef.apply)

  /**
   * The gateway-relative variant filename: the last segment of a stored `<bucket>/<object>` ref
   * (`media/ab/abc123/thumb.webp` -> `thumb.webp`). A well-formed ref has at least a bucket and an
   * object, so anything with fewer than two non-empty segments — an empty, slash-only, or
   * bucket-only ref (`""`, `"media"`, `"media/"`) — yields `""` rather than mistaking the bucket
   * name for a variant. The single source of the variant derivation, shared by the
   * search-projection path and the entity read path; never throws.
   */
  def variantOf(ref: String): String =
    val segments = ref.split('/').filter(_.nonEmpty)
    if segments.length >= 2 then segments.last else ""

  /**
   * Map a domain [[Derivative]] (`kind`, `<bucket>/<object>` ref) to its wire `{kind, variant}`.
   */
  def of(d: Derivative): DerivativeRef = DerivativeRef(d.kind, variantOf(d.ref))

  /**
   * Map a post's derivatives to their wire refs, dropping any whose `variant` can't be derived (a
   * malformed/partial ref) — the read API never exposes a `{kind, variant}` that would build an
   * un-fetchable media URL. Shared by both read paths so they filter identically.
   */
  def refsOf(derivatives: Seq[Derivative]): List[DerivativeRef] =
    derivatives.iterator.map(of).filter(_.variant.nonEmpty).toList
