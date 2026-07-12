# ingest-and-processing Specification

## Purpose
TBD - created by archiving change design-artemis-internals. Update Purpose after archive.
## Requirements
### Requirement: Upload write path

On upload, Artemis SHALL stream the bytes to Apollo while computing the md5 (storing the
original content-addressed at `originals/<md5[0:2]>/<md5>.<ext>`), create the `Post` entity
(status `pending`), publish a `ProcessMediaJob` to HermesMQ, and return `{postId, status:
pending}`. A checksum mismatch from Apollo SHALL abort the upload with no post created.

#### Scenario: An upload creates a pending post and enqueues processing
- **GIVEN** a valid uploaded image
- **WHEN** Artemis handles it
- **THEN** the original is stored content-addressed in Apollo, a pending `Post` is created, a `ProcessMediaJob` is published, and `{postId, pending}` is returned

#### Scenario: Edge case — a checksum mismatch aborts cleanly
- **GIVEN** a stream whose bytes fail Apollo's ingest checksum
- **WHEN** Artemis detects the mismatch
- **THEN** no post is created and no job is published

### Requirement: Consume processing results

Artemis SHALL consume Hephaestus results from HermesMQ: `MediaProcessed` SHALL issue
`RecordProcessed` (recording dimensions, derivatives, and phash) driving the post to
`active`; `MediaFailed` SHALL drive it to `failed`. Application SHALL be idempotent per
`jobId` so at-least-once redelivery does not double-apply.

#### Scenario: A processed result activates the post
- **GIVEN** a pending post whose job Hephaestus completed
- **WHEN** Artemis consumes `MediaProcessed`
- **THEN** it records the metadata/derivatives/phash and the post becomes active

#### Scenario: Edge case — duplicate delivery does not double-activate
- **GIVEN** a post already active from a `MediaProcessed`
- **WHEN** the same `MediaProcessed` (same `jobId`) is delivered again
- **THEN** it is a no-op and the post state is unchanged

### Requirement: Post-processing perceptual-duplicate warning

Using `MediaProcessed.phash`, Artemis SHALL compare an activated post against existing
posts (Hamming distance within a threshold) and, on a near match, SHALL flag the post as a
possible duplicate referencing the matched post — surfaced **after** activation (phash is
produced by Hephaestus asynchronously), never blocking the upload.

#### Scenario: A near-duplicate is flagged after activation
- **GIVEN** a newly active post whose phash is within the near-duplicate threshold of an existing post
- **WHEN** Artemis evaluates it
- **THEN** it flags the post as a possible duplicate referencing the existing post

#### Scenario: Edge case — a unique post is not flagged
- **GIVEN** an active post with no phash near-match
- **WHEN** Artemis evaluates it
- **THEN** no duplicate flag is set

