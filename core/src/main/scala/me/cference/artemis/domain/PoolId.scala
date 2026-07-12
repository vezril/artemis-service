package me.cference.artemis.domain

/**
 * A validated pool identifier. Constructible only via [[PoolId.from]]:
 *   - non-empty after trimming
 *   - at most [[MaxLength]] characters
 *
 * Pools are addressed `pool|<n>` at the entity layer; the domain keeps the identifier opaque.
 */
final case class PoolId private (value: String):
  override def toString: String = value

object PoolId:
  val MaxLength = 128

  def from(input: String): Either[DomainError, PoolId] =
    val trimmed = input.trim
    if trimmed.isEmpty then Left(DomainError.InvalidPoolId("must not be empty"))
    else if trimmed.length > MaxLength then
      Left(DomainError.InvalidPoolId(s"must be at most $MaxLength characters"))
    else Right(PoolId(trimmed))

  /** Rehydrate from a persisted event (trusted source). */
  def unsafe(value: String): PoolId = PoolId(value)
