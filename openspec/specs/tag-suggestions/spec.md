# tag-suggestions Specification

## Purpose
Artemis's side of AI auto-tagging: publish a tag-job when a post becomes active, and turn Argus's
raw tag suggestions into canonical, stored suggestions via the existing alias system — kept
separate from applied tags and never auto-applied.

## Requirements
### Requirement: Publish a tag-job when a post becomes active

Artemis SHALL publish a `TagJob` (carrying the `postId`, the Apollo **sample** reference, and
`mediaType`) to the `media.tag` topic when a post reaches `active`, so Argus can tag it out of
band. Publishing SHALL be best-effort — if it fails, the post remains active and untagged and
can be re-enqueued later; nothing blocks.

#### Scenario: An activated post enqueues a tag-job
- **GIVEN** a post that has just reached `active` after `RecordProcessed`
- **WHEN** Artemis processes the activation
- **THEN** it publishes a `TagJob` referencing the post's sample derivative to `media.tag`

#### Scenario: Edge case — tagging is decoupled from activation
- **GIVEN** Argus is down or backed up
- **WHEN** a post becomes active
- **THEN** the post is still active (just `unreviewed`); the job waits in the queue and is processed later

### Requirement: Alias-merge raw suggestions into canonical suggestions

On consuming `TagSuggestions`, Artemis SHALL canonicalize each raw tag through the existing
alias/implication resolution, dedup the result keeping the **maximum** confidence where models
or aliases collapse to the same canonical tag, and store the result as the post's suggestion
set (distinct from its applied tags), emitting `SuggestionsRecorded` and flagging the post
`needs-review`.

#### Scenario: Two vocabularies merge via aliases
- **GIVEN** raw suggestions `outdoor` (ram, 0.8) and `outdoors` (wd, 0.9) with an alias `outdoor → outdoors`
- **WHEN** Artemis processes them
- **THEN** the stored suggestion set contains one `outdoors` at confidence 0.9

#### Scenario: Edge case — redelivered suggestions do not duplicate
- **GIVEN** a post whose `TagSuggestions` were already recorded
- **WHEN** the same `TagSuggestions` (same `postId`) is delivered again
- **THEN** the suggestion set is re-recorded equivalently (idempotent), not appended twice

### Requirement: Suggestions are stored separately from applied tags

The suggestion set SHALL be kept distinct from the post's applied tag set and SHALL NOT be
auto-applied; the applied tag set changes only through an explicit accept (a `ChangeTags`).

#### Scenario: Suggestions do not appear in search until accepted
- **GIVEN** a post with a stored suggestion set but no applied tags
- **WHEN** a tag search runs for one of the suggested tags
- **THEN** the post does not match (suggestions are not applied tags)

#### Scenario: Edge case — a post can have applied tags and pending suggestions at once
- **GIVEN** a post that already has some applied tags
- **WHEN** suggestions arrive
- **THEN** both coexist — the applied set unchanged, the suggestion set pending review
