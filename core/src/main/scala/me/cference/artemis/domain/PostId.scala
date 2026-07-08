package me.cference.artemis.domain

/**
 * A validated post identifier. Constructible only via [[PostId.from]]:
 *   - non-empty after trimming
 *   - at most [[MaxLength]] characters
 *
 * Posts are addressed `post|<n>` at the entity layer; the domain keeps the identifier opaque so it
 * can carry either the bare number or the full entity key without re-validation downstream.
 */
final case class PostId private (value: String):
  override def toString: String = value

object PostId:
  val MaxLength = 128

  def from(input: String): Either[DomainError, PostId] =
    val trimmed = input.trim
    if trimmed.isEmpty then Left(DomainError.InvalidPostId("must not be empty"))
    else if trimmed.length > MaxLength then
      Left(
        DomainError.InvalidPostId(s"must be at most $MaxLength characters, was ${trimmed.length}")
      )
    else Right(PostId(trimmed))

  /**
   * Escape hatch for trusted sources (e.g. recovery from a persisted event whose value was
   * validated when first created). Not for untrusted input.
   */
  def unsafe(value: String): PostId = PostId(value)
