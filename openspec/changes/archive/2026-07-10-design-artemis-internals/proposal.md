# Change: design-artemis-internals

> **Design capture (explore mode).** Records Artemis's internal architecture — the
> event-sourced write side, the projection read models, ingest, and the API that serves
> the Muses contract. Builds on `design-artemis-tag-search` (tag model + DSL). No code
> is implemented by this change.

## Why

Artemis is the catalog heart. The tag model and search DSL are captured; Muses has
written the API contract it needs. This pins down the pieces in between: how posts are
event-sourced, how the search read models are projected, how ingest drives the async
media pipeline, and how the API + media are served — so Artemis can be built as the third
member of the Apollo/Hermes event-sourced family.

## Decisions carried in from exploration

| Decision | Choice |
|----------|--------|
| Architecture | **Event-sourced CQRS**, same house style as Apollo/Hermes (Pekko-persistence journal + Pekko Projections into Postgres read tables) |
| Aggregate scope | **Everything event-sourced** for uniformity (personal scale): the **Post** entity owns content, tags, lifecycle, rating, relationships **and favorites & scores** (as events); **Pool** is its own aggregate |
| Tag history | `TagsChanged` events **are** Danbooru's `post_versions` — edit history/undo for free |
| Perceptual dup | **phash stays in Hephaestus (async, option B)** — the dup warning surfaces once the post reaches `active`, not at upload time; Hephaestus processes when it has resources |
| Consistency | read models are **eventually consistent** (sub-second projection lag) — accepted; the post-view reads its own writes from the entity, Muses' optimistic edits cover the gap |
| Media delivery | Artemis exposes an **HTTP media gateway** that streams Apollo derivatives (with range support) — browsers never speak gRPC |
| Auth | **none in v1** (single-user); the multi-user seam is noted where events/queries gain a `user_id` |

## What Changes

- **post-aggregate** (new): the event-sourced `Post` entity — commands/events, lifecycle
  (`pending → active → deleted`), tag canonicalization on the write path, tag-edit history,
  and favorites/scores/rating/relationships as events.
- **pool-aggregate** (new): the `Pool` entity — ordered membership with add/remove/reorder
  and its own history.
- **read-model-projections** (new): Pekko Projections building the `posts`/`tags`/`pools`
  read tables the DSL queries, `post_count` maintenance, rebuildability, and the eventual-
  consistency model.
- **ingest-and-processing** (new): the upload write path (md5-on-stream to Apollo → `Post`
  pending → publish `ProcessMediaJob`), consuming Hephaestus `MediaProcessed`/`MediaFailed`
  to reach `active`/`failed`, and surfacing the async perceptual-dup warning.
- **catalog-api** (new): the pekko-http REST/JSON endpoints of the Muses contract (reads
  from projections, writes as entity commands) plus the HTTP media gateway.

## Impact

- Affected specs: the five capabilities above are **ADDED**.
- Depends on: `design-artemis-tag-search` (tag model + DSL), `design-hephaestus-contract`
  (the media job/result messages), Apollo (blobs), HermesMQ (jobs/events).
- Serves: the `design-muses-ui` API contract.
- Note — moving phash to option B means Artemis does **not** compute phash at upload; it
  relies on Hephaestus's `MediaProcessed.phash` (unchanged from the Hephaestus contract).
- Out of scope: multi-user auth/permissions/per-user favorites, moderation queues, tag
  wiki, saved searches — deferred to the multi-user era.
