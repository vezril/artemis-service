# Change: design-artemis-browse-default

> **Small contract change.** An empty search — no `tags` at all — becomes the **browse-all**
> query: the whole visible catalog, newest first. Calvin's ask: the gallery should show the most
> recent additions by default when no tags are set (Danbooru's post-index behavior).

## Why

Today an empty query is doubly rejected: `GET /posts` requires the `tags` parameter, and the DSL
parser's positive-anchor guardrail refuses a query with no terms. So the gallery with no search
shows nothing on a live deployment (fixture mode masked this by matching everything). The
positive-anchor rule exists so a *pure-negation* query can't force an unindexed scan — an empty
query is a different case entirely: browse-all is a plain `status <> 'deleted'` scan ordered
newest-first by the primary key, the cheapest query in the system, and the natural landing
experience for a gallery.

## What Changes

- `GET /posts` (and `GET /posts/facets`): the `tags` parameter becomes **optional**; absent or
  blank means **browse-all** — every non-deleted post, default order newest-first (`created_at`
  DESC, `id` tiebreak), same keyset paging, same `order` override, same envelope. Facets over an empty query
  aggregate the whole visible catalog.
- The parser and its guardrails are **untouched**: a blank query short-circuits in `SearchService`
  to an empty `QueryPlan` before parsing; a non-empty pure-negation query still rejects with
  `NoPositiveAnchor`.
- artemis-ui: no client change needed (it already omits `tags` when blank); the gallery's empty
  message differentiates "no posts yet" (blank query) from "no posts match this search".

## Capabilities

### Modified Capabilities
- `search-dsl`: the positive-anchor requirement is scoped to NON-EMPTY queries; a new requirement
  defines the empty query as browse-all.

## Impact

- `SearchService.planned`: blank `rawTags` → `Right(empty QueryPlan)` (order override still
  applied). One insertion covers search AND facets (both flow through `planned`).
- `SearchRoutes`: `tags` param optional on both routes, defaulting to `""`.
- Tests: empty-query search page (newest-first, keyset), empty-query facets, pure-negation still
  400, SqlCompiler empty-plan SQL pinned.

## Non-goals

- No parser/grammar change; no new ordering semantics; no UI redesign (the search page already
  renders whatever the query returns).
