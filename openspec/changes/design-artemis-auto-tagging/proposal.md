# Change: design-artemis-auto-tagging

> **Design capture (explore mode).** Artemis's side of AI auto-tagging: publishing tag-jobs,
> receiving + alias-merging suggestions, and the human review queue. Builds on
> `design-artemis-internals` and `design-artemis-tag-search`. No code implemented.

## Why

Argus (a dedicated Python service) suggests tags for uploaded media, but **Artemis owns
tags** — so Artemis drives the loop: it publishes the tag-job, receives raw suggestions,
merges them into its canonical vocabulary via the existing alias system, and runs the review
queue where a human accepts/tweaks them. Suggestions are never auto-applied.

## Decisions carried in from exploration

| Decision | Choice |
|----------|--------|
| Trigger | **Artemis publishes** the `TagJob` (post-`MediaProcessed`) — Hephaestus stays tag-agnostic |
| Input to Argus | the **sample** derivative ref (Argus reads it from Apollo) |
| Namespace merge | **reuse the alias/implication system** — Argus emits raw tags (both vocabularies), Artemis alias-resolves + dedups into canonical suggestions (max confidence on agreement) |
| Storage | suggestions stored **separately from applied tags** (with confidence + source), never auto-applied |
| Review | a **needs-review** flag + a review queue; **accept** promotes chosen suggestions into applied tags (a normal `ChangeTags`) and clears the flag |
| Policy | suggestions-only; human is the source of truth |

## What Changes

- **tag-suggestions** (new): publish `TagJob` to HermesMQ after a post is active; consume
  `TagSuggestions`; **alias-resolve + dedup** raw suggestions into canonical ones (keeping max
  confidence where models agree); store them separately from applied tags; flag the post
  `needs-review`.
- **tag-review** (new): a review-queue query (posts needing review), an **accept** command
  (promote selected suggestions to applied tags via `ChangeTags`, clear the flag), and a
  **dismiss/reject** path; the review status projected into the read model.

## Impact

- Affected specs: `tag-suggestions`, `tag-review` are **ADDED**.
- Depends on: `design-argus` (the `TagJob`/`TagSuggestions` contract + the `media.tag` queue),
  `design-artemis-tag-search` (the alias/implication canonicalization reused for the merge),
  `design-artemis-internals` (the Post aggregate + projections extended).
- Serves: `design-muses-review-queue` (the Muses Review view queries this).
- Data model: the Post aggregate gains a **suggestion set** (distinct from the applied tag
  set) and a **review status**; both projected for the review-queue query.
- Out of scope: auto-applying above a confidence threshold (possible later opt-in), running
  the models (that's Argus), multi-user review attribution.
