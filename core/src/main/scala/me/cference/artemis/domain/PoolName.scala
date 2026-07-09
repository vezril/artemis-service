package me.cference.artemis.domain

/**
 * A validated pool name (the human-facing title). Constructible only via [[PoolName.from]]:
 *   - non-empty after trimming
 *   - at most [[MaxLength]] characters
 */
final case class PoolName private (value: String):
  override def toString: String = value

object PoolName:
  val MaxLength = 255

  def from(input: String): Either[DomainError, PoolName] =
    val trimmed = input.trim
    if trimmed.isEmpty then Left(DomainError.InvalidPoolName("must not be empty"))
    else if trimmed.length > MaxLength then
      Left(DomainError.InvalidPoolName(s"must be at most $MaxLength characters"))
    else Right(PoolName(trimmed))

  /** Rehydrate from a persisted event (trusted source). */
  def unsafe(value: String): PoolName = PoolName(value)
