# Design: design-artemis-browse-default

## Decisions

### D1 — Short-circuit in `SearchService`, not the parser

`SearchService.planned` returns an empty `QueryPlan` (`includes`/`excludes`/`orGroups`/
`predicates` all empty, `order`/`limit` `None`, then the route's `order` override applied) when
`rawTags.trim.isEmpty`, BEFORE the parser runs. Rationale: the parser's `EmptyQuery` /
`NoPositiveAnchor` guardrails protect real text queries (a pure negation must not force an
unindexed scan) and stay exactly as they are; browse-all is not a parse of anything — it is the
absence of a query. `SqlCompiler` composes an empty plan into `WHERE status <> 'deleted' ORDER BY
created_at DESC, id DESC` + keyset + LIMIT — the always-present status default guarantees a valid WHERE clause.

### D2 — Route params go optional, blank-as-absent

`SearchRoutes`: `parameters("tags".optional, …)` with `.getOrElse("")` on both `/posts` and
`/posts/facets`. Blank and absent are equivalent (both reach the D1 short-circuit), so the UI's
existing behavior — omitting the param when the box is empty — needs no change.

### D3 — Default order is already newest-first

The compiler's default order is `id DESC` with an `id` tiebreak; ids are ULIDs, so `id DESC` IS
most-recent-first. No new ordering work; `order:` overrides (score, favcount…) apply to browse-all
exactly as to any query.

## Risks

- **Full-catalog scan cost**: browse-all is `status <> 'deleted' ORDER BY id DESC LIMIT n` — a
  reverse PK walk, the cheapest page in the system. Facets over the whole catalog aggregate every
  visible post's tags; fine at personal scale and identical in cost to a very broad tag query,
  which the system already permits.
