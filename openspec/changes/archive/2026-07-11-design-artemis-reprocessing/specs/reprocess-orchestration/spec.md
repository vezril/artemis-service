# reprocess-orchestration

The manual reprocess command: select posts, choose a kind, and re-enqueue jobs to a separate
lower-priority lane — idempotently and resumably.

## ADDED Requirements

### Requirement: Manual reprocess with selection and kind

Artemis SHALL provide a **manual** reprocess command that takes a selection — a search DSL
query, `stale`, or a single post id — and a `kind` (`derivatives` | `tags` | `metadata`),
resolves the matching posts from the read model, and enqueues a job per post. Nothing SHALL
reprocess automatically.

#### Scenario: Reprocessing videos regenerates their derivatives
- **GIVEN** the operator runs reprocess `--select "filetype:webm" --kind derivatives`
- **WHEN** the command resolves the selection
- **THEN** a derivative-regeneration job is enqueued for each matching video post

#### Scenario: Edge case — a kind scopes what runs
- **GIVEN** reprocess `--kind derivatives`
- **WHEN** it runs
- **THEN** it re-runs Hephaestus derivative generation and does not re-tag or alter applied tags

#### Scenario: Edge case — re-tagging feeds the review queue
- **GIVEN** reprocess `--select "id:1284" --kind tags`
- **WHEN** it runs
- **THEN** Argus re-tags the post and the new suggestions return to the review queue (the post is re-flagged needs-review), never auto-applied

### Requirement: Separate lower-priority reprocess lane

Reprocess jobs SHALL be enqueued to a dedicated `media.reprocess` topic, distinct from
`media.ingest`, and the workers SHALL drain `media.ingest` before `media.reprocess` so a
backfill never starves new uploads.

#### Scenario: New uploads are processed ahead of a backfill
- **GIVEN** a large backfill queued on `media.reprocess` and a new upload on `media.ingest`
- **WHEN** the workers pull
- **THEN** the new upload's ingest job is handled before the pending reprocess jobs

#### Scenario: Edge case — reprocess still drains when ingest is idle
- **GIVEN** no new uploads
- **WHEN** the workers pull
- **THEN** they process the `media.reprocess` backlog until it is empty

### Requirement: Idempotent and resumable

Reprocessing SHALL be safe to re-run: content-addressed derivatives overwrite deterministic
paths, at-least-once redelivery is harmless, and a `stale`-selection reprocess SHALL be
resumable — re-running enqueues only posts still below the current version, so an interrupted
backfill continues rather than restarting.

#### Scenario: An interrupted stale backfill resumes
- **GIVEN** a `--select stale --kind derivatives` reprocess that was interrupted with some posts already updated
- **WHEN** it is run again
- **THEN** it enqueues only the posts still below the current version (the completed ones are skipped)

#### Scenario: Edge case — a duplicate job is harmless
- **GIVEN** a reprocess job redelivered by Hermes (at-least-once)
- **WHEN** the worker handles it again
- **THEN** it overwrites the same derivative paths with equivalent content and the post state is unchanged
