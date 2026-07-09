package me.cference.artemis.search

import me.cference.artemis.domain.{Tag, TagCanonicalization, TagGraph}

/**
 * Search-time alias resolution (search-dsl spec, "Search-time alias resolution"). Rewrites each
 * concrete (non-wildcard) tag term through the same alias chain used on the write path, so that
 * searching an antecedent (`catgirl`) finds posts stored under its consequent (`cat_girl`).
 *
 * Polarity is preserved, so `-catgirl` resolves to `-cat_girl` and `~catgirl` to `~cat_girl`.
 * Wildcard tag patterns are left untouched (they are expanded later against the tag index), as are
 * all metatags. Chain-follow and cycle-safety are delegated to [[TagCanonicalization.resolveAlias]]
 * — this stage never reimplements that traversal.
 */
object AliasResolver:

  def resolve(query: SearchQuery, graph: TagGraph): SearchQuery =
    SearchQuery(query.terms.map(rewrite(_, graph)))

  private def rewrite(term: Term, graph: TagGraph): Term =
    term match
      case TagTerm(polarity, pattern) if !pattern.hasWildcard =>
        Tag.from(pattern.text) match
          case Right(tag) =>
            val resolved = TagCanonicalization.resolveAlias(tag, graph.aliases)
            TagTerm(polarity, TagPattern(resolved.value))
          case Left(_) => term
      case other => other
