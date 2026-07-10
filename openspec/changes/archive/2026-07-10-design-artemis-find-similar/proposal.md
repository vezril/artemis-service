# Change: design-artemis-find-similar

> **Design capture (explore mode).** Reverse-image / "find similar" search. Tier 1
> (perceptual-hash near-duplicate) is the active design; Tier 2 (embedding-based semantic
> similarity) is documented as a future enhancement. Builds on `design-hephaestus-contract`
> (phash) and `design-artemis-internals` (read model). No code implemented.

## Why

Every post already has a **perceptual hash** (computed by Hephaestus for dedup). That
fingerprint makes "find near-duplicates / variants / edits of this image" almost free — a
Hamming-distance query over data you already store. It generalizes the upload dedup warning
into a first-class feature, and enables reverse-image lookup ("do I already have this?").

The deferred "similarity service" turns out **not to be a service** — Tier 1 is a Postgres
query in Artemis.

## Decisions carried in from exploration

| Decision | Choice |
|----------|--------|
| Tier 1 (now) | **phash Hamming search** — a query in Artemis over the phash in the read model; brute-force scan is fast at personal scale (no separate service, no new infra) |
| Tier 2 (later) | **embedding-based semantic similarity** — documented as a future enhancement (see design.md), NOT built now |
| Scale | brute-force is fine for now; a BK-tree/index is the scale path if ever needed |

## What Changes

- **similarity-search** (new, Tier 1): near-duplicate search by perceptual-hash Hamming
  distance — from a post or from an arbitrary uploaded image (reverse lookup) — returning
  posts within a threshold, ordered by distance, served from the read model.

## Impact

- Affected specs: `similarity-search` is **ADDED** (Tier 1 only).
- Uses: the phash already stored per post (`design-hephaestus-contract`, `design-artemis-internals`).
- Serves: a "similar" affordance on the post page, reverse-image lookup, and generalizes the
  upload dedup warning.
- Tier 2 dependency (future): Argus emits a CLIP embedding + Artemis stores it in `pgvector`
  for cosine nearest-neighbor "visually similar" — a `kind: metadata` reprocess would embed
  the existing library (ties to `design-artemis-reprocessing`).
- Out of scope (this change): embeddings, `pgvector`, semantic similarity (all Tier 2, future).
