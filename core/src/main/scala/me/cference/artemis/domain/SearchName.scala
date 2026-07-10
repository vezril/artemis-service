package me.cference.artemis.domain

/**
 * A validated saved-search name (the human-facing label). Constructible only via
 * [[SearchName.from]]:
 *   - non-empty after trimming
 *   - at most [[MaxLength]] characters
 */
final case class SearchName private (value: String):
  override def toString: String = value

object SearchName:
  val MaxLength = 128

  def from(input: String): Either[DomainError, SearchName] =
    val trimmed = input.trim
    if trimmed.isEmpty then Left(DomainError.InvalidSearchName("must not be empty"))
    else if trimmed.length > MaxLength then
      Left(DomainError.InvalidSearchName(s"must be at most $MaxLength characters"))
    else Right(SearchName(trimmed))

  /** Rehydrate from a persisted event (trusted source). */
  def unsafe(value: String): SearchName = SearchName(value)
