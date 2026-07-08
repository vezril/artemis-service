# Design: Artemis Internals

The event-sourced catalog. Captured in explore mode; no implementation. Third member of
the Apollo/Hermes event-sourced family (Pekko-persistence journal + Pekko Projections).

## Three layers

```
   WRITE (entities)            JOURNAL            READ (projections)          API
   ┌──────────────┐   events   ┌─────────┐  tail  ┌────────────────────┐   ┌──────────┐
   │ Post entity  │──────────▶ │ Postgres │──────▶│ posts(tags[] GIN)  │──▶│ pekko-http│
   │ Pool entity  │            │ journal  │       │ tags(pg_trgm)      │   │ REST/JSON │
   └──────────────┘            └─────────┘        │ pools · favorites  │   │ + media GW│
      commands                                     (rebuildable)           └──────────┘
```

## Post aggregate (everything event-sourced)

```
   Post entity  id: post|<n>       lifecycle: pending → active → deleted
     commands: CreatePost · RecordProcessed(dims,derivatives,phash) · ChangeTags
               · SetRating · SetSource · SetParent · Favorite/Unfavorite · Score
               · Delete/Restore
     events:   PostCreated · MediaProcessed · TagsChanged · RatingChanged · SourceChanged
               · ParentSet · Favorited/Unfavorited · Scored · Deleted/Restored
```

- **Favorites & scores are events** (per the "everything ES" decision) — uniform, and at
  personal volume the journal churn is negligible. `TagsChanged` doubles as the tag-edit
  history (`post_versions`).
- **Tag canonicalization** (alias rewrite → transitive implication expansion → dedup, from
  `design-artemis-tag-search`) runs *before* `TagsChanged` is emitted, so the journal is
  always canonical.
- **Invariants**: no ops on a deleted post; generation of derivatives only via
  `RecordProcessed`; rating ∈ {g,s,q,e}.

## Pool aggregate

```
   Pool entity  id: pool|<n>
     commands: CreatePool · AddPost · RemovePost · Reorder · RenamePool · DeletePool
     events:   PoolCreated · PostAdded · PostRemoved · Reordered · …
```

Ordered membership with its own history — a separate aggregate so a pool's edits don't
touch post journals and vice versa.

## Read-model projections

Pekko Projections tail the journals into query-optimized Postgres tables (the hot path
never hits the entities):

```
   posts(id, tags text[], rating, score, fav_count, width, height, duration, filetype,
         md5, phash, parent_id, status, created_at, media_refs…)   GIN(tags) + btrees
   tags(name, category, post_count)                                GIN(name) pg_trgm
   pools(id, name, …) · pool_posts(pool_id, post_id, position)
```

- `post_count` and `fav_count` are projection-maintained (and reconcilable — denormalized
  counts drift).
- **Rebuildable**: drop a read table and replay the journal to reconstruct it — a core ES
  benefit (schema changes, index changes, bug fixes to projection logic).
- The search DSL compiles entirely against `posts`/`tags` — never the entities.

## Consistency model (the one tradeoff, accepted)

```
   write (entity)  strongly consistent per-post   → post-view reads its own write: fresh
   read  (search)  eventually consistent (~<1s)    → a tag search reflects an edit after
                                                       the projection catches up
```

Accepted for personal single-user use. Muses' optimistic edits cover the post-view; the
brief search lag is imperceptible at this scale.

## Ingest & the async spine (Artemis's side)

```
   1. Muses POST /posts (bytes + metadata)
   2. Artemis streams to Apollo, computing md5 → original at originals/<md5[0:2]>/<md5>.<ext>
   3. CreatePost (pending); publish ProcessMediaJob → HermesMQ media.ingest
   4. return {postId, status: pending}   (Muses polls status)
   5. Hephaestus processes; publishes MediaProcessed(phash, derivatives, dims)
   6. Artemis consumes it → RecordProcessed → projection flips status = active
      (MediaFailed → status = failed)
   7. perceptual-dup: from MediaProcessed.phash, Artemis compares (Hamming) vs existing
      posts and surfaces a "possible duplicate" notice on the now-active post (option B)
```

The dup warning is **post-processing** (option B) — Hephaestus owns phash and runs when it
has resources; the user reviews/deletes a flagged dup after it goes active.

## Catalog API + media gateway

Serves the Muses contract: reads from projections, writes as entity commands.

```
   GET /posts?tags=<DSL>&order=&cursor=  → projection query (keyset)
   GET /posts/{id}                       → projection + entity for own-writes freshness
   GET /posts/facets?tags=<DSL>          → tags across the result set, by category + count
   GET /tags/autocomplete?q=&context=    → tags pg_trgm
   POST /posts · PATCH /posts/{id}/tags · POST /posts/{id}/favorite · /score
   GET /pools · POST /pools · POST /pools/{id}/posts
   ── media gateway ──
   GET /media/{md5}/{variant}            → stream from Apollo (gRPC) over HTTP w/ range
```

**Faceting** powers Muses' tags-in-results panel: aggregate the tags of the posts matching
the query, by category, with counts. At personal scale a live aggregate over the matching
`posts.tags[]` is fine; the scale path (if ever needed) is a precomputed related-tags table,
as Danbooru does.

## Multi-user seam (future, additive)

Event-sourcing makes it additive: `Favorited`/`Scored`/`TagsChanged` gain a `user_id`;
add a `User` aggregate + per-user favorites projection + permission checks at the API.
No re-architecture — the history is already there.

## Out of scope

Auth/permissions, per-user favorites, moderation queues, tag wiki, saved searches,
comments, notes — the multi-user era.
