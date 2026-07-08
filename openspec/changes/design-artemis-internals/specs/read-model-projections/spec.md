# read-model-projections

Pekko Projections that tail the entity journals into query-optimized Postgres read tables
— what the search DSL and the API read from. The write entities are never queried directly.

## ADDED Requirements

### Requirement: Search read model built by projection

A projection SHALL consume `Post` events into a `posts` read table carrying the
denormalized fields the DSL queries (`tags text[]`, `rating`, `score`, `fav_count`,
`width`, `height`, `duration`, `filetype`, `md5`, `phash`, `parent_id`, `status`,
`created_at`, media refs), with a GIN index on `tags` and btree indexes for the metatag
predicates. A `tags` table SHALL carry `(name, category, post_count)` with a `pg_trgm`
index for autocomplete.

#### Scenario: A tag edit reaches the search read model
- **GIVEN** a `TagsChanged` event on a post
- **WHEN** the projection processes it
- **THEN** the post's `tags[]` in the read table reflects the new set and it matches/leaves searches accordingly

#### Scenario: Edge case — projected counts are maintained and reconcilable
- **GIVEN** posts gaining and losing a tag
- **WHEN** the projection processes the events
- **THEN** `tags.post_count` tracks membership and can be recomputed from the read model if it drifts

### Requirement: Rebuildable read models

The read tables SHALL be reconstructable by replaying the journal from the beginning, so a
read table can be dropped and rebuilt (for a schema/index change or a projection-logic
fix) without touching the write side.

#### Scenario: A read table rebuilds from the journal
- **GIVEN** an emptied `posts` read table
- **WHEN** the projection replays the `Post` journal from offset zero
- **THEN** the read table is reconstructed to match the current entity states

#### Scenario: Edge case — replay is idempotent
- **GIVEN** a projection that reprocesses already-applied events (e.g. after an offset reset)
- **THEN** the resulting read table is the same (upserts keyed by id make replay idempotent)

### Requirement: Eventual consistency of the read side

The read models SHALL be eventually consistent with the write side (a bounded projection
lag), and the design SHALL account for it: the post-view MAY read the entity for
read-your-writes freshness, while list/search reflect an edit once the projection catches up.

#### Scenario: Search reflects an edit after the projection catches up
- **GIVEN** a tag edit just committed on the entity
- **WHEN** a tag search runs before the projection has processed it
- **THEN** the result may briefly lag, then reflects the edit once the projection advances (no lost update)

#### Scenario: Edge case — the post itself is immediately correct
- **GIVEN** the same just-committed edit
- **WHEN** the post-view reads the post by id from the entity
- **THEN** it shows the new tags immediately (read-your-writes), independent of projection lag
