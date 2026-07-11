# Design: Artemis auto-tagging

Artemis's side of the auto-tagging loop. Captured in explore mode; no implementation.

## The loop

```
   MediaProcessed → post active
        │
        │ Artemis publishes TagJob{postId, sample ref, mediaType} → HermesMQ media.tag
        ▼
   Argus tags the sample → publishes TagSuggestions{postId, [{tag,category?,confidence,source}]}
        │
        ▼ Artemis consumes:
        │   ① normalize is already done by Argus (surface form)
        │   ② ALIAS-RESOLVE each tag (existing canonicalization) → canonical form
        │   ③ dedup, keeping MAX confidence where models/aliases collapse to one tag
        │   ④ store as the post's SUGGESTION SET (separate from applied tags) · flag needs-review
        ▼
   Muses Review queue → user accepts/tweaks → ChangeTags(applied) → clear needs-review
```

## The namespace merge is just aliases

The clever bit: Argus emits raw tags from two vocabularies; Artemis's **existing** alias +
implication canonicalization is exactly the mechanism that unifies them.

```
   Argus raw:  outdoors(wd,.9) · outdoor(ram,.8) · tree(wd,.7) · tree(ram,.9) · woman(ram,.6)
        │ alias-resolve (you curate: outdoor→outdoors)  +  dedup(max)
        ▼
   canonical suggestions:  outdoors(.9) · tree(.9, both agreed) · woman(.6)
```

You grow the RAM→canonical alias map incrementally as you review — the first time you see
`outdoor`, you alias it to `outdoors` and it's merged forever. Cross-model agreement naturally
surfaces high-confidence tags to the top of the review list.

## Data model (aggregate additions)

```
   Post entity gains:
     suggestionSet   [{ tag(canonical), confidence, source }]   ← distinct from applied tags
     reviewStatus    unreviewed | reviewed                       ← set when suggestions arrive
   events:  SuggestionsRecorded(postId, suggestions) · SuggestionsReviewed(postId)
```

Suggestions are **not** applied tags — the post keeps its (possibly empty) applied tag set
*and* a pending suggestion set. Accepting is a normal `ChangeTags` (so it flows through
canonicalization + history like any edit); it also emits `SuggestionsReviewed` to clear the
flag. Rejecting just clears the flag without applying. Both are projected so the review-queue
query can list `unreviewed` posts.

## Idempotency

`SuggestionsRecorded` is applied idempotently per `postId` — Argus is at-least-once, so a
redelivered `TagSuggestions` re-records equivalent suggestions without duplication (the
suggestion set is replaced/merged, not appended blindly).

## Publishing the job

Artemis publishes the `TagJob` when a post becomes active (right after `RecordProcessed`),
referencing the **sample** derivative so Argus fetches a small image. Tagging is best-effort
and decoupled — if Argus is down or behind, the post is simply active-and-unreviewed until
suggestions arrive; nothing blocks.

## Out of scope

Auto-apply-above-threshold (a later opt-in), the models themselves (Argus), per-user review
attribution (multi-user era).
