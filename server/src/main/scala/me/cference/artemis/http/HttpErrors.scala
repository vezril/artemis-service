package me.cference.artemis.http

import me.cference.artemis.domain.{DomainError, DomainException}
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}

/**
 * Central mapping from a domain failure (bridged through `DomainException`) to an HTTP status,
 * shared by the catalog and saved-search routes. Keeping the exhaustive `DomainError` match in one
 * place means a newly-added error case fails to compile HERE (with `-Werror` on non-exhaustive
 * matches) rather than degrading to a masked `500` in whichever route forgot to handle it.
 */
object HttpErrors:

  /** Map a bridged domain failure to its HTTP status; anything else is a `500`. */
  def statusOf(ex: Throwable): StatusCode =
    ex match
      case DomainException(error) => statusFor(error)
      case _ => StatusCodes.InternalServerError

  /** The human-readable message for a bridged failure (or a generic fallback). */
  def messageOf(ex: Throwable): String =
    ex match
      case DomainException(error) => error.message
      case other => Option(other.getMessage).getOrElse("unexpected error")

  /** Total mapping from every `DomainError` to a status. */
  def statusFor(error: DomainError): StatusCode =
    error match
      case DomainError.PostNotFound | DomainError.PoolNotFound | DomainError.PostNotInPool |
          DomainError.SavedSearchNotFound =>
        StatusCodes.NotFound
      case DomainError.PostAlreadyExists | DomainError.PoolAlreadyExists |
          DomainError.SavedSearchNameConflict =>
        StatusCodes.Conflict
      case _: DomainError.InvalidRating | _: DomainError.InvalidTag |
          _: DomainError.InvalidTagCategory | _: DomainError.InvalidPostId |
          _: DomainError.InvalidPoolId | _: DomainError.InvalidPoolName |
          _: DomainError.InvalidSearchName | _: DomainError.InvalidDimensions =>
        StatusCodes.BadRequest
