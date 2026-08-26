# Change: design-artemis-pool-reads

> **The read-side gap for pools.** The pool aggregate — create / add / remove / reorder / rename /
> delete, plus `GET /pools/{id}` — is already built, event-sourced, and projected into `pools` +
> `pool_posts`. What's missing is the read surface a UI needs to **browse** pools and **render a
> pool as a gallery**. This adds exactly those two projection-backed read endpoints; no new tables,
> events, or write paths.

## Why

Artemis already has a complete pool write surface (`POST /pools`, `POST/DELETE /pools/{id}/posts`,
`PUT /pools/{id}/order`, `PATCH /pools/{id}`, `DELETE /pools/{id}`) and a read-your-writes
`GET /pools/{id}` that returns the ordered membership as a bare `[postId, …]` list. The read model
maintains `pools(id, name)` and `pool_posts(pool_id, post_id, position)` via a live projection.

But there is **no way to list pools** (the `pools` read table has no route), and **no way to render
a pool as a gallery** without an N+1 fan-out: `GET /pools/{id}` hands back post *ids* only, so a UI
would have to fetch each post individually to get its md5 + thumbnail derivatives. The artemis-ui
"Pools" slice needs a pools index (cards with a cover thumbnail and a count) and an ordered,
paginated gallery of hydrated post summaries. This change closes that read-side gap so the UI can be
built entirely against Artemis, consistent with how `GET /posts` already serves hydrated,
cursor-paginated summaries from the projection.

## What Changes

- **`GET /pools`** — a cursor-paginated list of pools for the browse view. Each entry carries the
  pool's `id`, `name`, member `postCount`, and a `cover` (the position-0 post rendered as a
  `PostSummary`, or `null` for an empty pool) so a card can show a thumbnail without a second call.
  Ordered by `name` then `id` with an opaque keyset cursor (never OFFSET), mirroring `GET /posts`.
- **`GET /pools/{id}/posts`** — the pool's members as **hydrated `PostSummary` rows in pool order**
  (`pool_posts.position`), cursor-paginated. Returns the same `{posts, nextCursor}` envelope as
  `GET /posts`, so the gallery reuses the existing summary/derivative rendering. A missing pool
  returns an empty page (the pool's existence is confirmed via `GET /pools/{id}`).
- Both endpoints are served **from the projection** (like `GET /posts`), not the entity. The
  existing entity-backed `GET /pools/{id}` is **unchanged** (still read-your-writes, still the bare
  ordered id list) — the editor keeps its authoritative, lag-free membership order.
- **Fix the `pool_posts.position` invariant** the reads depend on: today `removePoolPost` deletes
  without renumbering while `addPoolPost` appends at `COUNT(*)`, so a remove-then-add produces
  duplicate positions and removing the first member leaves no `position 0`. `removePoolPost` is
  changed to renumber the survivors to a dense 0-based sequence (a projection-side fix; entity,
  events, and API untouched). The read queries are *also* written to be correct over non-dense
  legacy data (composite `(position, post_id)` keyset; cover = lowest visible `(position, post_id)`),
  so they never drop/duplicate even before old rows self-heal.
- Members and cover exclude **soft-deleted** posts (`status <> 'deleted'`) to match `GET /posts`, and
  `postCount` is counted over that same visible set so a card's number equals the gallery it opens.

## Capabilities

### Modified Capabilities
- `catalog-api`: the "read endpoints served from projections" requirement gains the two concrete
  pool read endpoints (`GET /pools` list with covers; `GET /pools/{id}/posts` hydrated, ordered,
  paginated), spelled out with scenarios.

## Impact

- **New routes:** `GET /pools` and `GET /pools/{id}/posts`, added to `SearchRoutes` (the
  projection-backed read surface), **not** `CatalogRoutes` (the entity write / read-your-writes
  surface). Route ordering: `GET /pools/{id}/posts` (in SearchRoutes, composed first) is claimed
  before `CatalogRoutes`' `GET /pools/{id}` (`path(Segment)`) — same "search first" discipline that
  keeps `GET /posts/facets` ahead of `GET /posts/{id}`.
- **New read-model queries** in `ReadModelRepository`: a keyset pool-page query (with a visible
  `postCount`), a batched `DISTINCT ON` cover lookup, and a pool-ordered hydrated-members query. All
  reuse the existing `PostColumns` / `mapPostRow` / `parseDerivatives` machinery. Plus a one-statement
  **renumber** added to `removePoolPost` (D7). The cover's `= ANY($ids)` needs a Postgres **array
  bind** (not the scalar `.bind` used elsewhere in the repo).
- **New DTOs** in `SearchJson` (or a small `PoolReadJson`): `PoolSummary`, `PoolListResponse`;
  reuses `PostSummary` for the cover and the members. camelCase, matching the existing pool DTOs
  (`poolId`) and `PostSummary`.
- **No schema change** — `pools` and `pool_posts` (with `position` and the `pool_posts_pool_pos`
  index) already exist. **No new events, no HTTP write-endpoint change.** The only write-side touch is
  the D7 projection fix to how `removePoolPost` maintains `position` (a bug fix, not a contract
  change).

## Non-goals / out of scope

- **Reverse lookup** ("which pools contain post X") — no route/index here; a later slice for the
  post-detail "Pools" section.
- **`pool:` / `ordpool:` search metatags** — still stubbed as unsupported in `SqlCompiler`; a later
  slice. This change does not touch the DSL.
- Changing or deprecating the entity-backed `GET /pools/{id}` (kept as-is for read-your-writes).
- Pool cover *selection* (a chosen cover image) — the cover is deterministically the position-0
  member.
