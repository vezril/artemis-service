# catalog-api

Pool read endpoints served from the projection: a browseable pool list with covers, and a pool's
members hydrated in order.

## MODIFIED Requirements

### Requirement: Read endpoints served from projections

Artemis SHALL serve the read endpoints from the projection read models: `GET /posts` with a
DSL `tags` query, `order`, and keyset `cursor` (never OFFSET); `GET /posts/{id}` for post
detail; `GET /tags/autocomplete` (trigram, grammar-aware); pool reads; and **tag facets for
a query** (the tags occurring across the matching posts, grouped by category with a count
per tag) to power the gallery's tags-in-results panel. `GET /posts/{id}` MAY consult the
entity for read-your-writes freshness.

Pool reads served from the projection SHALL include:

- **`GET /pools`** — a keyset-cursor-paginated list of pools ordered by `name` then `id` (never
  OFFSET). Each entry SHALL carry the pool's `id`, `name`, member `postCount`, and a `cover` — the
  pool's **first visible member** rendered as a post summary, or `null` when the pool has no visible
  members. `postCount` SHALL count the same visible members the gallery returns (excluding
  soft-deleted / purged members), so a card's count matches the pool it opens.
- **`GET /pools/{id}/posts`** — the pool's members as hydrated post summaries in pool order,
  keyset-cursor-paginated, in the same `{posts, nextCursor}` envelope as `GET /posts`. It SHALL
  exclude soft-deleted members (as `GET /posts` does by default) and SHALL return each member
  exactly once across pages. It SHALL NOT 404 on an unknown or empty pool; it returns an empty page
  (pool existence is answered by the entity-backed `GET /pools/{id}`).

These pool reads are served from the projection and are eventually consistent; the entity-backed
`GET /pools/{id}` remains the read-your-writes source for a pool's name and ordered membership and
is unchanged. A malformed or foreign read `cursor` SHALL be a `400`, never a `500`.

#### Scenario: Search returns a keyset page
- **GIVEN** a query `1girl cat_ears order:score`
- **WHEN** `GET /posts` is called
- **THEN** it returns a page of matching post summaries ordered by score with a next `cursor`, sourced from the read model

#### Scenario: Tag facets for a query
- **GIVEN** a query matching a set of posts
- **WHEN** the facets endpoint for that query is called
- **THEN** it returns the tags occurring across the matching posts, grouped by category, each with a count of how many matches carry it

#### Scenario: Edge case — autocomplete is context-aware
- **GIVEN** `GET /tags/autocomplete?q=rating:&context=metatag`
- **WHEN** it is served
- **THEN** it returns the rating enum values rather than tag names

#### Scenario: List pools with a cover
- **GIVEN** two pools, one with members and one empty
- **WHEN** `GET /pools` is called
- **THEN** it returns both pools ordered by name, each with its `postCount`, the non-empty pool carrying its position-0 member as `cover` and the empty pool carrying `cover: null`, plus a `nextCursor` when more pools remain

#### Scenario: A pool's members are returned hydrated and in order
- **GIVEN** a pool whose members are ordered p3, p1, p2
- **WHEN** `GET /pools/{id}/posts` is called
- **THEN** it returns hydrated post summaries (md5 + derivatives) in the order p3, p1, p2 from the read model, with a keyset `cursor` when the pool exceeds one page

#### Scenario: Edge case — members of an unknown pool are an empty page
- **GIVEN** a pool id that does not exist (or exists with no members)
- **WHEN** `GET /pools/{id}/posts` is called
- **THEN** it returns an empty `posts` list with no `nextCursor` and a `200` (not a `404`)

#### Scenario: Edge case — a malformed pool read cursor is a 400
- **GIVEN** `GET /pools?cursor=<garbage>` (or the same on `GET /pools/{id}/posts`)
- **WHEN** it is served
- **THEN** it returns `400` with an error message, never a `500`

#### Scenario: Edge case — soft-deleted members are hidden and not counted
- **GIVEN** a pool whose first member is soft-deleted
- **WHEN** `GET /pools` and `GET /pools/{id}/posts` are called
- **THEN** the deleted member is absent from the members page and from `postCount`, and the pool's `cover` is the next visible member (or `null` if no visible member remains)
