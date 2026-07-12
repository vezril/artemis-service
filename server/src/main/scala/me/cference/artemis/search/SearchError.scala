package me.cference.artemis.search

/**
 * A unified search failure surfaced by [[SearchService]]: the parse, plan, and compile stages each
 * fail with their own typed error ([[ParseError]], [[PlanError]], [[CompileError]]), and this trait
 * collapses all three into one value the HTTP layer maps to a single `400 Bad Request` (a search
 * failure is always a bad request, never a 500). Errors-as-values: the service returns `Left`,
 * never throws.
 */
sealed trait SearchError:
  def message: String

object SearchError:

  /** The DSL failed to parse (empty query, pure negation, malformed metatag, too many tags). */
  final case class BadQuery(message: String) extends SearchError

  /** Parsing succeeded but planning failed (e.g. an over-broad wildcard expansion). */
  final case class Unplannable(message: String) extends SearchError

  /** The plan could not compile to SQL (unsupported metatag/order, bad value, malformed cursor). */
  final case class Uncompilable(message: String) extends SearchError
