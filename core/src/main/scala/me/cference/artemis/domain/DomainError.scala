package me.cference.artemis.domain

/**
 * Typed domain failures. Smart constructors and state transitions return these via `Either`; the
 * domain never throws for expected invalid input.
 */
sealed trait DomainError:
  /** Human-readable reason naming the violated rule. */
  def message: String

object DomainError:
  final case class InvalidPostId(message: String) extends DomainError
  final case class InvalidPoolId(message: String) extends DomainError
  final case class InvalidPoolName(message: String) extends DomainError
  final case class InvalidSearchName(message: String) extends DomainError
  final case class InvalidTag(message: String) extends DomainError
  final case class InvalidTagCategory(message: String) extends DomainError
  final case class InvalidRating(message: String) extends DomainError
  final case class InvalidDimensions(message: String) extends DomainError

  case object PostAlreadyExists extends DomainError:
    val message = "post already exists"

  case object PostNotFound extends DomainError:
    val message = "post does not exist (never created or deleted)"

  case object PoolAlreadyExists extends DomainError:
    val message = "pool already exists"

  case object PoolNotFound extends DomainError:
    val message = "pool does not exist (never created or deleted)"

  case object PostNotInPool extends DomainError:
    val message = "post is not a member of this pool"

  case object SavedSearchNotFound extends DomainError:
    val message = "saved search does not exist"

  case object SavedSearchNameConflict extends DomainError:
    val message = "a saved search with that name already exists"
