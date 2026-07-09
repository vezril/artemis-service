package me.cference.artemis.domain

import scala.annotation.tailrec

/**
 * The pure write-path tag canonicalization pipeline (tag-model spec). Given a requested tag set and
 * a [[TagGraph]], produce the canonical set that rides in a `TagsChanged` event, in a fixed order:
 * (1) alias rewrite — each tag is resolved along its alias chain to a terminal consequent; (2)
 * transitive implication expansion — the full reachable set of implied consequents is added; (3)
 * dedup — inherent in the `Set` result.
 *
 * Aliases resolve before implications so implication rules key off canonical names. Both
 * alias-chain resolution and implication expansion carry a visited set, so cycles terminate instead
 * of looping.
 */
object TagCanonicalization:

  def canonicalize(tags: Set[Tag], graph: TagGraph): Set[Tag] =
    val aliased = tags.map(resolveAlias(_, graph.aliases))
    expandImplications(aliased, graph.implications)

  /**
   * Follow the alias chain to a terminal consequent; a cycle stops at the first repeat. Public so
   * the search path (`AliasResolver`) applies the exact same single-tag resolution as the write
   * path, rather than duplicating the chain-follow logic.
   */
  def resolveAlias(tag: Tag, aliases: Map[Tag, Tag]): Tag =
    @tailrec
    def loop(current: Tag, seen: Set[Tag]): Tag =
      aliases.get(current) match
        case Some(next) if !seen.contains(next) => loop(next, seen + current)
        case _ => current
    loop(tag, Set.empty)

  /** Breadth-first closure over implications; the visited set makes cycles terminate. */
  private def expandImplications(seed: Set[Tag], implications: Map[Tag, Set[Tag]]): Set[Tag] =
    @tailrec
    def loop(frontier: Set[Tag], acc: Set[Tag]): Set[Tag] =
      val next = frontier.flatMap(tag => implications.getOrElse(tag, Set.empty)) -- acc
      if next.isEmpty then acc
      else loop(next, acc ++ next)
    loop(seed, seed)
