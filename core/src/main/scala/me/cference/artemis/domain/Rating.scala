package me.cference.artemis.domain

/**
 * Content rating, the Danbooru fixed set: general, sensitive, questionable, explicit. The single
 * canonical letter code is the persisted form. Constructible from a raw string only via
 * [[Rating.from]]; a value outside the set is a typed rejection.
 */
enum Rating(val code: String):
  case General extends Rating("g")
  case Sensitive extends Rating("s")
  case Questionable extends Rating("q")
  case Explicit extends Rating("e")

object Rating:
  def from(input: String): Either[DomainError, Rating] =
    values.find(_.code == input.trim.toLowerCase) match
      case Some(rating) => Right(rating)
      case None => Left(DomainError.InvalidRating(s"must be one of g, s, q, e; was '$input'"))
