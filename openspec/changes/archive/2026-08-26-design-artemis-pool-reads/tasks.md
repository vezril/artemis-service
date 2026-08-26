# Tasks: design-artemis-pool-reads

## 0. Read-model invariant fix (D7)

- [x] 0.1 `removePoolPost` — after the `DELETE`, renumber survivors to a dense 0-based sequence
  (`WITH ranked AS (SELECT post_id, ROW_NUMBER() OVER (ORDER BY position, post_id) - 1 AS new_pos …)
  UPDATE …`), so `position` stays a faithful dense mirror of the entity vector.
- [x] 0.2 Extend `PoolProjectionIT`: after add p0,p1,p2 → remove p1 → add p3, assert positions are
  `{p0:0, p2:1, p3:2}` (dense, unique, no duplicate `2`, no missing `0` after a first-member remove).

## 1. Read-model queries (`ReadModelRepository`)

- [x] 1.1 Add `listPools(cursor: Option[PoolListCursor], limit: Int)` — keyset page over `pools`
  ordered `lower(name) ASC, id ASC`, selecting `id, name` and a **visible** `postCount`
  (`SELECT COUNT(*) FROM pool_posts pp JOIN posts po ON po.id = pp.post_id WHERE pp.pool_id =
  pools.id AND po.status <> 'deleted'`; narrow the `bigint` to `Int`). Request `limit + 1` rows.
  Keyset predicate is the **row-value form** `(lower(name), id) > ($1, $2)` (or the expanded
  `lower(name) > $1 OR (lower(name) = $1 AND id > $2)`) — never `lower(name) > $1 AND id > $2`.
- [x] 1.2 Add `poolCovers(poolIds: Seq[String]): Future[Map[String, PostRow]]` — one batched
  `SELECT DISTINCT ON (pp.pool_id) pp.pool_id, <PostColumns> FROM pool_posts pp JOIN posts po ON
  po.id = pp.post_id WHERE pp.pool_id = ANY($1) AND po.status <> 'deleted' ORDER BY pp.pool_id,
  pp.position, pp.post_id`, mapping via `mapPostRow`, keyed by `pool_id`. **Bind `$1` as a Postgres
  array** (not scalar `.bind`). Empty `poolIds` → `Map.empty` (no query).
- [x] 1.3 Add `poolPostsHydrated(poolId, cursor: Option[PoolPostsCursor], limit)` — `pool_posts pp
  JOIN posts po ON po.id = pp.post_id WHERE pp.pool_id = $1 AND po.status <> 'deleted' AND
  (pp.position, pp.post_id) > ($cursorPos, $cursorPostId) ORDER BY pp.position, pp.post_id LIMIT
  $limit + 1`. First page omits the keyset predicate (or uses a `(-1, "")` sentinel).
- [x] 1.4 IT coverage (`PoolProjectionIT` or a new `PoolReadIT`): list name-ordering + visible
  `postCount`; cover present / absent (empty pool → no cover; soft-deleted first member → cover is
  the next visible; all-deleted → `None`); hydrated members preserve pool order; keyset paging
  returns each member exactly once across pages **even with a duplicate-position legacy row**;
  soft-deleted members are excluded from members + count.

## 2. Pool read cursors

- [x] 2.1 Add `PoolListCursor(lowerName: String, id: String)` and `PoolPostsCursor(position: Int,
  postId: String)`, each encoded as **`base64url(JSON)`** (field-safe for names with spaces / any
  VARCHAR — never space/colon-delimited). Provide `encode`/`decode`, `decode: Either[error, cursor]`.
- [x] 2.2 Decode failures surface as a `400` at the route (reuse the DSL reads' bad-request mapping
  / an `ErrorResponse` message) — never a 500. Unit-test round-trip encode/decode (including a pool
  name with a space and a colon) and a garbage-cursor rejection.

## 3. DTOs + JSON (`SearchJson` / `PoolReadJson`)

- [x] 3.1 Add `PoolSummary(id, name, postCount, cover: Option[PostSummary])` and
  `PoolListResponse(pools: List[PoolSummary], nextCursor: Option[String])` with spray-json
  `jsonFormatN` (camelCase). Reuse `PostSummary` for the cover.
- [x] 3.2 Add `poolListResponse(rows, covers, nextCursor)` builder that zips page rows with the
  cover map (`summaryOf` on the cover `PostRow`, `None` when absent) into `PoolListResponse`.
- [x] 3.3 The members endpoint reuses the existing `SearchResponse` DTO + `SearchJson.summaryOf` —
  no new DTO needed there.

## 4. Routes (`SearchRoutes`)

- [x] 4.1 Inject two backend fns: `listPoolsFn(cursor: Option[String], limit: Int)` and
  `poolPostsFn(poolId: String, cursor: Option[String], limit: Int)` (fakeable without a DB, like
  `searchFn`). Wire them in `Main` to the new repository methods (decode cursor → query →
  build DTO); keep a `DefaultPageSize` clamp.
- [x] 4.2 `GET /pools?cursor=&limit=` → `path("pools") & get & parameters(...)` → `listPoolsFn` →
  `PoolListResponse`. Bad cursor → 400.
- [x] 4.3 `GET /pools/{id}/posts?cursor=&limit=` → `path("pools" / Segment / "posts") & get &
  parameters(...)` → `poolPostsFn` → `SearchResponse`. Bad cursor → 400; unknown/empty pool → 200
  empty page.
- [x] 4.4 Confirm route composition order in `Main`: `SearchRoutes` (with these) composed before
  `CatalogRoutes` so `GET /pools/{id}/posts` and `GET /pools` are claimed ahead of `CatalogRoutes`'
  `GET /pools/{id}`. Add a route-level test asserting `GET /pools/{id}/posts` is not captured by the
  `GET /pools/{id}` handler.

## 5. Route tests (`SearchRoutesSpec` or a new spec)

- [x] 5.1 `GET /pools` returns pools name-ordered with `postCount` and cover (present/`null`), and a
  `nextCursor` when a second page exists; following the cursor returns the remaining pools with no
  overlap.
- [x] 5.2 `GET /pools/{id}/posts` returns hydrated summaries in pool order; paging via `nextCursor`
  yields each member once (assert no drop/duplicate); an unknown/empty pool → `200` empty page.
- [x] 5.3 Malformed `cursor` on both endpoints → `400` with an error body.
- [x] 5.4 Soft-deleted member is excluded from `GET /pools/{id}/posts`, from the list `postCount`,
  and as a cover (cover falls through to the next visible member; all-deleted pool → `cover: null`).

## 6. Docs / spec sync

- [x] 6.1 Update the API doc/README surface (wherever the endpoint list lives) to include
  `GET /pools` and `GET /pools/{id}/posts` with their shapes.
- [x] 6.2 `openspec validate design-artemis-pool-reads --strict` clean; full `sbt` gate green
  (scalafmt, compile, test, coverage) before opening the PR.
