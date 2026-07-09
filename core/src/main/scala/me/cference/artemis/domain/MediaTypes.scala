package me.cference.artemis.domain

/**
 * Media value types carried by `RecordProcessed`/`MediaProcessed`. Each is a thin, immutable
 * wrapper; payload bytes never enter the journal, only the facts derived from processing.
 *
 * These serialize to the journal via Jackson defaults — `Md5`/`Phash`/`Filetype` as `{"value":...}`
 * and `Derivative` as `{"kind":...,"ref":...}`. Those field names ARE the on-disk journal contract:
 * renaming a field (or the case-class parameter it derives from) breaks recovery of
 * already-persisted events. Evolve additively only.
 */

/** MD5 content hash of the original upload (identity/dedup key). */
final case class Md5(value: String)

/** Perceptual hash used for near-duplicate detection. */
final case class Phash(value: String)

/** Detected media container/codec, e.g. `image/png`, `video/webm`. */
final case class Filetype(value: String)

/**
 * A generated derivative (thumbnail, preview, sample) produced by processing. `kind` names the
 * variant (e.g. `thumbnail`, `sample`); `ref` locates its bytes in the blob store.
 */
final case class Derivative(kind: String, ref: String)

/**
 * Pixel dimensions of the media, with an optional duration in milliseconds for time-based media
 * (video/animation). Constructible only via [[Dimensions.from]] which enforces positive extents; a
 * still image has no duration.
 */
final case class Dimensions private (width: Int, height: Int, duration: Option[Long]):
  override def toString: String =
    duration match
      case Some(ms) => s"${width}x$height@${ms}ms"
      case None => s"${width}x$height"

object Dimensions:
  def from(
      width: Int,
      height: Int,
      duration: Option[Long] = None
  ): Either[DomainError, Dimensions] =
    if width <= 0 || height <= 0 then
      Left(
        DomainError.InvalidDimensions(s"width and height must be positive, was ${width}x$height")
      )
    else if duration.exists(_ < 0) then
      Left(DomainError.InvalidDimensions("duration must be non-negative"))
    else Right(Dimensions(width, height, duration))

  /** Rehydrate from a persisted event (trusted source). */
  def unsafe(width: Int, height: Int, duration: Option[Long] = None): Dimensions =
    Dimensions(width, height, duration)
