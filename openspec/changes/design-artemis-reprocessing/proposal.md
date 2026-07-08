# Change: design-artemis-reprocessing

> **Design capture (explore mode).** Regenerating derived data (thumbnails, transcodes,
> tags, metadata) for **existing** media — not just new uploads. Builds on
> `design-artemis-internals`, `design-hephaestus-contract`, `design-artemis-auto-tagging`.
> No code implemented.

## Why

The system generates derivatives (thumbnails, samples, transcodes), a perceptual hash,
and (via Argus) tag suggestions **at upload time**. But you sometimes change *how* that's
generated — a bigger thumbnail, a fixed bug, a new transcode rung, a better tagger — or you
restore originals from backup and the derivatives are gone. Reprocessing applies those to
the **existing** library instead of only future uploads.

Because the pipeline is content-addressed + idempotent, reprocessing is **not a new
pipeline** — it re-enqueues the same jobs the workers already handle. The design is mostly
"pick the images, re-trigger the work, and don't redo what's already current."

## Decisions carried in from exploration

| Decision | Choice |
|----------|--------|
| Version stamps | **Yes** — each post records the version it was processed with; reprocessing only redoes **stale** posts (out-of-date), making backfill incremental + resumable |
| Priority | **Separate lane** — a `media.reprocess` topic drained at lower priority than `media.ingest`, so new uploads are never starved by a big backfill |
| Trigger | **Manual** — the operator kicks off a reprocess; nothing reprocesses automatically |
| Selection | reuse the **search DSL** (all · a query · one id) plus "only stale" |
| Scope | **kinds** — `derivatives` (Hephaestus) · `tags` (Argus re-tag) · `metadata` — redo only what changed |

## What Changes

- **processing-versions** (new): posts carry a `derivativeSpecVersion` and a `taggerVersion`
  (stamped when processed/tagged); the read model can find posts **stale** relative to the
  current version.
- **reprocess-orchestration** (new): a manual reprocess command that selects posts (DSL /
  stale / id) and a kind, enqueues jobs to the separate lower-priority `media.reprocess`
  lane, and is idempotent + resumable (re-running skips already-current posts; scoped kinds
  don't disturb unrelated data).

## Impact

- Affected specs: `processing-versions`, `reprocess-orchestration` are **ADDED**.
- Cross-service (notes): a new HermesMQ `media.reprocess` topic; **Hephaestus and Argus
  consume it at lower priority than `media.ingest`** (drain ingest first). The job/result
  contracts carry the spec version so the stamp reflects what actually ran.
- Closes the loop on: **#1 media backup** (restore originals → reprocess-all regenerates
  derivatives — the reason backup is cheap) and **#2 auto-tagging** (re-tag with a new model
  = a reprocess of kind `tags` → new suggestions → the review queue).
- Out of scope: automatic/scheduled reprocessing (manual only in v1), a fancy priority
  scheduler beyond "ingest before reprocess," per-derivative-type granularity below "kind."
