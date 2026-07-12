package me.cference.artemis.search

/**
 * Errors that can abort compilation of a [[SearchQuery]] into a [[QueryPlan]]. Errors-as-values:
 * the planner returns `Left(PlanError)` rather than throwing.
 */
sealed trait PlanError:
  def message: String

/**
 * A wildcard tag pattern matched more concrete tags than the expansion cap allows (search-dsl spec,
 * "Wildcard tag expansion is bounded"). Carries the offending pattern and guidance to refine, and
 * is both the [[WildcardExpander]] failure and a terminal [[PlanError]].
 */
final case class WildcardTooBroad(pattern: String) extends PlanError:
  def message: String = s"wildcard '$pattern' matches too many tags — refine your search"
