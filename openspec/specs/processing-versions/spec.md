# processing-versions Specification

## Purpose
Version stamps that record how a post was processed, so reprocessing can target only out-of-date
posts (incremental, resumable backfill).

## Requirements
### Requirement: Posts record the version they were processed with

A post SHALL record a `derivativeSpecVersion` (stamped when Hephaestus processing is
recorded) and a `taggerVersion` (stamped when tag suggestions are recorded), reflecting the
generation logic that actually ran. These stamps SHALL be projected into the read model.

#### Scenario: Processing stamps the derivative version
- **GIVEN** the current derivative spec version is `v4`
- **WHEN** a post is processed (or reprocessed) by Hephaestus and Artemis records the result
- **THEN** the post's `derivativeSpecVersion` is stamped `v4`

#### Scenario: Edge case — tagging stamps the tagger version independently
- **GIVEN** a post processed for derivatives at `v4` and later tagged by tagger `t2`
- **WHEN** the suggestions are recorded
- **THEN** the post's `taggerVersion` is `t2` while its `derivativeSpecVersion` stays `v4` (the stamps are independent)

### Requirement: Stale posts are queryable

The read model SHALL support finding posts that are **stale** for a given kind — those whose
recorded version is below the current version (`derivativeSpecVersion < current` for
derivatives, `taggerVersion < current` for tags) — so a reprocess can target only them.

#### Scenario: Stale posts are found after a version bump
- **GIVEN** posts stamped `v3` and a current derivative spec version of `v4`
- **WHEN** the stale-for-derivatives query runs
- **THEN** it returns the `v3` posts (and not any already at `v4`)

#### Scenario: Edge case — nothing stale returns empty
- **GIVEN** all posts are at the current version
- **WHEN** the stale query runs
- **THEN** it returns no posts (a completed backfill)
