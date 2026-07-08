# Tasks: design-artemis-reprocessing

TDD throughout. Builds on the Post aggregate + projections + the job publishing from
`design-artemis-internals` / `design-artemis-auto-tagging`.

## 1. Version stamps

- [ ] 1.1 (test) `derivativeSpecVersion` stamped on `RecordProcessed`; `taggerVersion` on suggestions
- [ ] 1.2 (test) stamps projected; stale query returns posts below the current version (empty when all current)
- [ ] 1.3 (impl) stamps in events/state + projection + config for the current versions

## 2. Reprocess command

- [ ] 2.1 (test) select by DSL query / `stale` / id; enqueue one job per matching post
- [ ] 2.2 (test) `kind` scoping: derivatives (Hephaestus) · tags (Argus → review) · metadata; no cross-kind effects
- [ ] 2.3 (impl) resolve selection from the read model + enqueue

## 3. Separate lane

- [ ] 3.1 (impl) enqueue reprocess jobs to `media.reprocess` (distinct from `media.ingest`)
- [ ] 3.2 (note/cross-service) Hephaestus + Argus consume `media.reprocess` at lower priority (ingest first)

## 4. Safety

- [ ] 4.1 (test) `stale` reprocess is resumable — re-run enqueues only still-stale posts
- [ ] 4.2 (test) redelivered reprocess job is idempotent (overwrite, no state change)

## 5. Trigger surface

- [ ] 5.1 (impl) manual trigger: an admin endpoint / CLI (`POST /reprocess {select, kind}`)
- [ ] 5.2 (test) integration: bump version → stale found → reprocess → workers regenerate → stamps updated
