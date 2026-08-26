# Design: design-artemis-pool-reads

## Context

The pool aggregate is complete (domain, `PoolEntity`, `PoolProjection`/`PoolProjectionHandler`,
tables). Reads split by freshness/shape the same way posts do:

| Endpoint | Backed by | Shape | Freshness |
|---|---|---|---|
| `GET /pools/{id}` (existing) | entity (`PoolEntity.Get`) | `{id, name, posts:[postId,…]}` | read-your-writes |
| `GET /pools` (new) | projection (`pools`, `pool_posts`, `posts`) | `{pools:[PoolSummary], nextCursor}` | eventually consistent |
| `GET /pools/{id}/posts` (new) | projection (`pool_posts` ⋈ `posts`) | `{posts:[PostSummary], nextCursor}` | eventually consistent |

This mirrors the existing posts split: `GET /posts/{id}` is entity read-your-writes; `GET /posts`
is projection-backed and hydrated. The gallery reads are *browse* reads where a few ms of projection
lag is fine, and the UI applies optimistic updates on writes anyway. The editor keeps the
authoritative, lag-free ordered id list from the unchanged entity endpoint.

## Goals / Non-Goals

**Goals:** a browseable pools list with enough per-card data (name, count, cover thumbnail) to
render without a follow-up call; an ordered, paginated, hydrated gallery of a pool's members; full
reuse of the existing summary/cursor/derivative machinery; zero schema/event/write change.

**Non-Goals:** reverse lookup, `pool:` DSL metatags, cover selection, touching the write path or the
entity read endpoint.

## Decisions

### D1 — Two new endpoints, served from the projection, added to `SearchRoutes`

`SearchRoutes` is the projection-backed read surface (it already owns `GET /posts`, `/posts/facets`,
`/tags/autocomplete`) and is composed **before** `CatalogRoutes` in `Main`. Adding the pool reads
here (rather than to `CatalogRoutes`) keeps the entity write surface's constructor/spec untouched
and, crucially, gets the routing precedence right for free:

- `GET /pools/{id}/posts` — a `path("pools" / Segment / "posts")` in `SearchRoutes` — is matched
  before `CatalogRoutes`' `GET /pools/{id}` (`path(Segment)` under `pathPrefix("pools")`), exactly
  as `GET /posts/facets` is claimed ahead of `GET /posts/{id}`.
- `GET /pools` (`path("pools")`, end) does not collide with `POST /pools` (method-guarded) or
  `GET /pools/{id}` (segment-guarded).

`SearchRoutes` gains two injected backend functions (same fakeable-without-a-DB pattern as
`searchFn`/`facetsFn`): `listPoolsFn(cursor, limit)` and `poolPostsFn(poolId, cursor, limit)`,
wired in production to new `ReadModelRepository` methods and in tests to plain stubs.

### D2 — `GET /pools` ordering & keyset cursor: `(lower(name), id)`

The `pools` table is `(id VARCHAR PK, name VARCHAR)` — no `created_at`, and `id` is a user-chosen
string (not monotonic). A stable, human-meaningful order is **`lower(name) ASC, id ASC`** (id
breaks name ties; the PK guarantees total order). The keyset predicate is the **row-value form**
`(lower(name), id) > ($1, $2)` (equivalently `lower(name) > $1 OR (lower(name) = $1 AND id > $2)`) —
**not** the naive `lower(name) > $1 AND id > $2`, which drops rows whose name advances but id is
smaller. The cursor is the opaque-encoded `(lower(name), id)` of the last **returned** row — same
"never OFFSET" rule as `GET /posts`. The page SQL requests `limit + 1` rows; the extra row's presence
yields `nextCursor` (taken from the retained row, not the dropped one) and is dropped from the
response. `postCount` is a correlated count over the **visible** membership
(`pool_posts pp JOIN posts po ON po.id = pp.post_id WHERE pp.pool_id = pools.id AND po.status <>
'deleted'`), so the card's number matches the gallery it opens (D3). `COUNT(*)` is a `bigint` —
narrow to `Int` explicitly.

> The `pools` table is small (homelab scale: tens–hundreds). A full sort is cheap; no new index is
> required. If it ever grows, a `(lower(name), id)` index is the follow-up — noted, not built.

### D3 — Cover fetch: batched `DISTINCT ON` over the *visible* first member (not `position = 0`)

Rendering a pool card needs the first member's md5 + derivatives. The cover is **not** keyed to
`position = 0` — see D7: `position` is not a reliable dense sequence, so a pool whose first member
was removed may have no `position 0` at all. The cover is instead "the lowest-position **visible**
member", fetched batched for the whole page in one query:

```sql
SELECT DISTINCT ON (pp.pool_id) pp.pool_id, <PostColumns>
FROM pool_posts pp JOIN posts po ON po.id = pp.post_id
WHERE pp.pool_id = ANY($ids) AND po.status <> 'deleted'
ORDER BY pp.pool_id, pp.position, pp.post_id
```

- `DISTINCT ON (pp.pool_id)` + that `ORDER BY` yields exactly one row per pool: its first member by
  `(position, post_id)`. The `post_id` tiebreak makes it deterministic even when two members share a
  position (the D7 legacy case) or during a non-atomic reorder (see Risks).
- `po.status <> 'deleted'` excludes soft-deleted members — matching `GET /posts`, which hides
  `deleted` by default (`SqlCompiler` deleted-by-default). A purged member (hard-deleted from
  `posts`) is likewise absent because the `JOIN posts` is inner.
- `PostColumns` is selected from `posts` alias `po` so unqualified names (`id`, `status`, …) map
  cleanly via `mapPostRow`; `pp.pool_id` is read separately and never collides. Rows map to
  `(poolId, PostRow)`, keyed back onto the page; a pool with no *visible* members → `cover = None`.
- `$ids` is bound as a Postgres array (`= ANY($1)` needs an array bind, not the scalar `.bind`
  pattern used elsewhere in the repo — call this out in the impl).

The `pool_posts_pool_pos (pool_id, position)` index serves the ordering. `postCount` is computed in
the page query over the **same visible set** (D2), so the card's count matches what the gallery
actually renders — no "42 posts" that opens to 41.

### D4 — `GET /pools/{id}/posts`: hydrated visible members in pool order, keyset on `(position, post_id)`

```sql
SELECT <PostColumns>
FROM pool_posts pp JOIN posts po ON po.id = pp.post_id
WHERE pp.pool_id = $1
  AND po.status <> 'deleted'
  AND (pp.position, pp.post_id) > ($cursorPosition, $cursorPostId)
ORDER BY pp.position, pp.post_id
LIMIT $limit + 1
```

The keyset is the **composite `(position, post_id)`**, not `position` alone. `post_id` is unique per
pool (`pool_posts` PK is `(pool_id, post_id)`), so the composite is a *total, unique* order even when
`position` has duplicates or gaps (D7) — which is what prevents the drop/duplicate-across-pages bug
the naive `position > cursor` keyset would hit. The cursor encodes the last `(position, post_id)`
seen (opaque; first page uses a `(-1, "")` sentinel, or omits the predicate). `po.status <>
'deleted'` gives the same soft-delete hiding as `GET /posts`; purged members drop via the inner join.
Rows map through `mapPostRow` → `SearchJson.summaryOf` → `PostSummary`, so the response is the same
`{posts, nextCursor}` envelope as `GET /posts`.

A non-existent (or empty) pool yields an empty first page with `nextCursor = null` — existence is a
concern of the entity `GET /pools/{id}`, not this gallery feed, so this endpoint never 404s
(consistent with `GET /posts`). This asymmetry (the entity endpoint 404s, this one 200-empties) is
intentional and documented in the spec.

### D5 — DTOs

```scala
final case class PoolSummary(id: String, name: String, postCount: Int, cover: Option[PostSummary])
final case class PoolListResponse(pools: List[PoolSummary], nextCursor: Option[String])
```

- Reuses `PostSummary` for the cover (and, in `/{id}/posts`, for every member) → one thumbnail-
  rendering code path in the UI, and the cover already carries md5 + derivatives + dimensions.
- camelCase (`postCount`, `nextCursor`) — matches `PostSummary` and the existing pool DTOs
  (`poolId`). The `/{id}/posts` endpoint reuses the existing `SearchResponse` DTO unchanged.
- Formats live beside the routes' backing JSON (in `SearchJson`, or a small `PoolReadJson`
  companion) with explicit spray-json `jsonFormatN`.

### D6 — Cursor codec: base64url of a JSON payload (field-safe, not space-delimited)

The two pool cursors must be encoded **field-safely**. `PoolName` permits spaces and `pools.id` is a
user-chosen VARCHAR, so a space/colon-delimited payload (`p:<lower-name> <id>`) is ambiguous and
would silently corrupt page 2 for a pool named "my summer pool". Instead each cursor is
`base64url(JSON)` — list `{"n":"<lower-name>","id":"<pool-id>"}`, members
`{"p":<position>,"id":"<post-id>"}`. JSON quoting handles spaces/colons/any VARCHAR content;
base64url keeps it opaque and URL-safe. The existing DSL cursor (`search/Cursor.scala`) stays
untouched — these codecs are local, so the pool reads don't inherit its compiled-order assumptions.
Decode returns `Either[err, cursor]`; a malformed/foreign cursor is a `400`, never a 500 (a
`SearchError`-style message) — matching the DSL reads' "a bad read input is a 400" rule.

### D7 — Fix the load-bearing invariant: `pool_posts.position` must be dense after a remove

The read queries above are written to be *robust* to a messy `position` (composite `(position,
post_id)` keyset, cover by lowest `(position, post_id)`), but the underlying defect is worth fixing
at the source: the projection does **not** keep `position` dense. `addPoolPost` derives position from
`COUNT(*)` and `removePoolPost` deletes without renumbering, so `remove p1` from `[p0,p1,p2]` leaves
`{p0:0, p2:2}`; a later `addPoolPost p3` lands at `position = 2` (two rows at position 2), and a
first-member removal leaves no `position 0`. This drifts the projection from the entity's truth (an
always-dense ordered `Vector[PostId]`).

Fix: **`removePoolPost` renumbers the surviving rows to a dense 0-based sequence** right after the
delete:

```sql
-- after DELETE FROM pool_posts WHERE pool_id = $1 AND post_id = $2
WITH ranked AS (
  SELECT post_id, ROW_NUMBER() OVER (ORDER BY position, post_id) - 1 AS new_pos
  FROM pool_posts WHERE pool_id = $1
)
UPDATE pool_posts pp SET position = r.new_pos
FROM ranked r WHERE pp.pool_id = $1 AND pp.post_id = r.post_id
```

This restores `position` as a faithful dense mirror of the entity vector (relative order preserved;
`reorderPool` already rewrites 0..n-1, append at `COUNT(*)` is then always correct). It is a
projection-side fix: the entity, events, and API are untouched, so it composes with the read
endpoints rather than expanding the write contract.

**Belt and suspenders.** Renumber-on-remove only fixes *future* removes; a live DB may already hold
duplicate positions from past ones, and `reorderPool` runs non-atomically (see Risks). The composite
`(position, post_id)` keyset and lowest-`(position, post_id)` cover are correct over *any* position
data, dense or not, so reads never drop or duplicate regardless of legacy rows or in-flight reorders.
D7 buys order *fidelity* going forward; the robust queries buy *safety* now.

## Risks / Trade-offs

- **Projection lag on a just-created/just-modified pool.** A pool created a moment ago may not yet
  appear in `GET /pools`, and a just-added post may lag in `GET /pools/{id}/posts`. Accepted: these
  are browse reads; the editor uses the read-your-writes entity endpoint + optimistic UI. Same
  trade-off already accepted for `GET /posts`.
- **Two round trips for the list page + cover.** The page query (pools + counts) and the batched
  `DISTINCT ON` cover query are separate; chosen over a lateral join for clarity and testability, and
  the cover query is a single indexed batched lookup. Fine at homelab scale.
- **Non-atomic `reorderPool`.** `reorderPool` writes each row's new position with a separate
  `update()` on its own short-lived connection (`sequentially`, not one transaction / not the
  projection's session), so mid-reorder a reader can momentarily see duplicate positions. The
  composite `(position, post_id)` read keyset and the tiebroken cover make the reads *safe* across
  this window (no drop/duplicate); the only visible effect is a brief, self-healing reordering. Making
  `reorderPool` transactional is a worthwhile follow-up but is **not** required for read correctness
  here, so it's out of scope.
- **`postCount` vs a soft-deleted/purged member.** Counting over the *visible* joined set (D2/D3)
  keeps the card count equal to the gallery length. The trade-off: a member that is soft-deleted (or
  hard-purged, leaving a dangling `pool_posts` row — there is no FK, DDL) silently drops from both the
  count and the gallery. Accepted and documented: the pool reflects what a viewer can actually see.
- **No `pools.name` index.** Acceptable now (small table); the follow-up is a one-line DDL add.

## Migration / Rollout

Additive on the read surface, plus one projection-side behavior fix (D7: `removePoolPost`
renumbers). No DB migration, no event/schema change, no change to any *existing endpoint's* contract.
D7 changes only the `position` values the projection writes on a remove — already-corrupt legacy rows
are tolerated by the robust read queries and self-heal on the next remove/reorder. Ships in one PR;
the artemis-ui "Pools" slice is the downstream consumer (separate design, `design-artemis-ui-pools`).

## Open Questions

- ~~Should `PoolSummary.cover` fall back to a later member when the first is soft-deleted?~~
  **Resolved** (was the review's narrow open question): the cover is the lowest-`(position, post_id)`
  **visible** (non-deleted) member, so it already skips soft-deleted / removed / purged first members
  and is `null` only when no visible member remains.
- Make `reorderPool` transactional (a real follow-up, tracked out of scope) — see Risks. Not blocking
  read correctness.
