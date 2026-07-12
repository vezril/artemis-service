package me.cference.artemis.domain

/**
 * A canonical tag name. Constructible only via [[Tag.from]], which normalizes per the tag-model
 * spec:
 *   - lowercased
 *   - leading/trailing whitespace trimmed
 *   - internal whitespace and runs of underscores collapsed to a single underscore
 *
 * Names empty after normalization, or exceeding [[MaxLength]], are rejected with a typed error and
 * never stored.
 */
final case class Tag private (value: String):
  override def toString: String = value

object Tag:
  val MaxLength = 170

  def from(input: String): Either[DomainError, Tag] =
    val normalized = normalize(input)
    if normalized.isEmpty then Left(DomainError.InvalidTag("must not be empty after normalization"))
    else if normalized.length > MaxLength then
      Left(
        DomainError.InvalidTag(s"must be at most $MaxLength characters, was ${normalized.length}")
      )
    else Right(Tag(normalized))

  /**
   * The canonical normalization used by [[from]], exposed for the canonicalization pipeline: lower,
   * trim, and collapse runs of whitespace/underscores to a single underscore.
   */
  def normalize(input: String): String =
    input.trim.toLowerCase.replaceAll("[\\s_]+", "_").replaceAll("^_+|_+$", "")

  /** Rehydrate from a persisted event (trusted source). */
  def unsafe(value: String): Tag = Tag(value)
