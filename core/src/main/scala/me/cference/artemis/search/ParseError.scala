package me.cference.artemis.search

/**
 * Typed parse-time failures for the search DSL. [[SearchParser.parse]] returns these via `Either`;
 * the parser never throws for malformed input. Mirrors the domain's
 * [[me.cference.artemis.domain .DomainError]] style: a sealed trait with a human-readable
 * `message`.
 */
sealed trait ParseError:
  /** Human-readable reason naming the violated rule. */
  def message: String

object ParseError:

  /** The query was empty or blank — no terms to parse. */
  case object EmptyQuery extends ParseError:
    val message = "query is empty; at least one term is required"

  /** A term was empty after its marker — e.g. a bare `~`, or a trailing `-`. */
  case object EmptyTerm extends ParseError:
    val message = "empty term; a tag or metatag is required after '~' or '-'"

  /**
   * A `name:value` term was malformed (empty name, empty value, or a comparison with no scalar).
   */
  final case class MalformedMetatag(message: String) extends ParseError

  /** A `a..b` range term was malformed (no bound on either side). */
  final case class MalformedRange(message: String) extends ParseError

  /** The query had no positive anchor — every term was a negation. */
  case object NoPositiveAnchor extends ParseError:
    val message = "query requires at least one positive term or metatag anchor"

  /** The query exceeded the positive-tag ceiling. */
  final case class TooManyPositiveTags(message: String) extends ParseError
