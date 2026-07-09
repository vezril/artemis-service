package me.cference.artemis.search

import me.cference.artemis.domain.Tag

/**
 * The compiled shape of a search query, produced by [[SearchPlanner]] after alias resolution and
 * wildcard expansion. It is the structural hand-off to §4/§5 (SQL generation + keyset pagination),
 * which this stage deliberately does NOT perform — the plan merely *carries* the tag structure and
 * the metatag predicates/order/limit for later compilation.
 *
 *   - `includes` — tags every match must carry (AND / set-containment).
 *   - `excludes` — tags no match may carry (also the union of any negated-wildcard expansions).
 *   - `orGroups` — each element is an at-least-one group; groups are ANDed together. The `~`
 *     flat-OR set is one group; each positive wildcard's expansion is its own group. An **empty**
 *     group is significant: a positive wildcard that expands to zero live tags (`cat_*` with no
 *     matches) is kept as `Set.empty` here, so §4 MUST render it as `FALSE` (the query has no
 *     results), not skip it. (An empty flat-`~` set — no `~` terms — is instead omitted, since it
 *     is the absence of an OR constraint rather than an impossible one.)
 *   - `predicates` — the carried metatag filters (everything except a positive `order:`/`limit:`).
 *   - `order` / `limit` — extracted from a positive `order:`/`limit:` metatag. `limit` is carried
 *     as-is (the page-size ceiling clamp is a §4/§5 concern); a non-numeric `limit:` is ignored
 *     (stays `None` → the default page size applies).
 */
final case class QueryPlan(
    includes: Set[Tag],
    excludes: Set[Tag],
    orGroups: Seq[Set[Tag]],
    predicates: Seq[MetaTerm],
    order: Option[String],
    limit: Option[Int]
)
