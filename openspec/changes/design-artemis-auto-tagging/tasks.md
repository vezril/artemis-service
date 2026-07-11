# Tasks: design-artemis-auto-tagging

TDD throughout. Builds on the Post aggregate + projections from `design-artemis-internals`.

## 1. Aggregate additions

- [x] 1.1 (test) `SuggestionsRecorded` folds a suggestion set (separate from applied tags); idempotent per postId
- [x] 1.2 (test) accept applies chosen suggestions via `ChangeTags` + emits `SuggestionsReviewed`; reject-all still reviews
- [x] 1.3 (impl) events + `decide`/`evolve` for suggestions + review status

## 2. Alias-merge

- [x] 2.1 (test) raw suggestions canonicalize via alias/implication resolution; dedup keeps max confidence
- [x] 2.2 (impl) the merge pipeline (reuse tag-model canonicalization)

## 3. Messaging

- [x] 3.1 (impl) publish `TagJob` (sample ref) to `media.tag` when a post becomes active (best-effort, decoupled)
- [x] 3.2 (test/impl) consume `TagSuggestions` → alias-merge → `SuggestionsRecorded`; idempotent

## 4. Projections + query

- [x] 4.1 (test) review status projected (`unreviewed`/`reviewed`); rebuildable
- [x] 4.2 (impl) review-queue query (ordered for batch review) served from the read model

## 5. API

- [x] 5.1 (test/impl) `GET /review` (queue), `POST /posts/{id}/review` (accept selected + tweaks / reject)
- [x] 5.2 (test) integration: activate → job published → (mock Argus) suggestions → queued → accept → tagged
