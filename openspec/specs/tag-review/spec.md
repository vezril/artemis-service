# tag-review Specification

## Purpose
The human review workflow over stored tag suggestions: a queue of un-reviewed posts and an accept
that promotes chosen suggestions into applied tags (via a normal tag edit), clearing the flag.

## Requirements
### Requirement: Review-queue query

Artemis SHALL expose a query for posts that are `unreviewed` (have a pending suggestion set),
served from the read model, ordered so a batch upload can be reviewed together (e.g. by upload
time), with each post's suggestions (canonical tag, confidence, source) available for display.

#### Scenario: The queue lists posts awaiting review
- **GIVEN** several posts flagged `needs-review` from a batch upload
- **WHEN** the review-queue query runs
- **THEN** it returns those posts with their suggestion sets, ordered for batch review

#### Scenario: Edge case — reviewed posts leave the queue
- **GIVEN** a post that has been reviewed
- **WHEN** the review-queue query runs
- **THEN** that post no longer appears

### Requirement: Accept promotes chosen suggestions to applied tags

An accept command SHALL take the subset of a post's suggestions the user approves and apply
them as tags via `ChangeTags` (so they flow through canonicalization + tag-edit history like
any edit), then mark the post `reviewed` (emitting `SuggestionsReviewed`). Accepting SHALL be
able to include user-added tags and exclude rejected suggestions.

#### Scenario: Accepting selected suggestions tags the post
- **GIVEN** a post with suggestions `[cat_ears, 1girl, blurry]` and the user approves `[cat_ears, 1girl]`
- **WHEN** the accept command runs
- **THEN** `cat_ears` and `1girl` become applied tags (via `ChangeTags`), `blurry` is not, and the post is marked `reviewed`

#### Scenario: Edge case — reviewing with zero acceptances still clears the flag
- **GIVEN** a post whose suggestions are all rejected
- **WHEN** the user finishes review
- **THEN** no tags are applied and the post is marked `reviewed` (it leaves the queue)

### Requirement: Review status is projected

The `needs-review` / `reviewed` status SHALL be maintained in the read model so the queue
query and the UI can filter on it, and it SHALL be rebuildable from the journal like other
projected state.

#### Scenario: Status reflects in the read model
- **GIVEN** `SuggestionsRecorded` then `SuggestionsReviewed` events on a post
- **WHEN** the projection processes them
- **THEN** the post's projected review status transitions `unreviewed → reviewed`

#### Scenario: Edge case — status rebuilds on projection replay
- **GIVEN** an emptied read model
- **WHEN** the journal is replayed
- **THEN** each post's review status is reconstructed from its events
