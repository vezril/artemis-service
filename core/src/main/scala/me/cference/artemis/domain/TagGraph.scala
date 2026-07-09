package me.cference.artemis.domain

/**
 * The alias and implication rules the write-path canonicalization pipeline consults. Pure data,
 * threaded into [[PostDomain.decide]] for `ChangeTags` so the decider stays pure and total (the
 * entity layer supplies a snapshot loaded from the tag read model).
 *
 *   - `aliases` maps an antecedent tag to its direct consequent; chains resolve transitively to a
 *     terminal consequent.
 *   - `implications` maps an antecedent to the set of tags it directly implies; expansion is
 *     transitive over the reachable set.
 */
final case class TagGraph(aliases: Map[Tag, Tag], implications: Map[Tag, Set[Tag]])

object TagGraph:
  val empty: TagGraph = TagGraph(Map.empty, Map.empty)
