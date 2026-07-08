# Design: Artemis reprocessing

Regenerating derived data for existing media. Captured in explore mode; no implementation.

## The idea in one line

```
   The original is the source of truth. Everything else is generated from it and can be
   re-generated anytime. Reprocessing = re-run that generation over images you pick.
```

Nothing new is built — reprocessing re-enqueues the **same** `ProcessMediaJob` / `TagJob`
the workers already handle. Content-addressing makes it safe (derivatives overwrite
deterministic paths; at-least-once redelivery is harmless).

## Version stamps → only redo what's stale

```
   post carries:  derivativeSpecVersion  (stamped when Hephaestus processes it)
                  taggerVersion          (stamped when Argus/Artemis records suggestions)
   the operator bumps the CURRENT version when they change the spec (new thumb size, model…)
   reprocess "stale" = posts where the relevant version < current
        → touches only out-of-date posts · resumable (re-run skips already-current ones)
        · new uploads always use current · progress = count(version == current)
```

Without stamps, every reprocess grinds the whole library; with them, backfill is incremental
and crash-resumable. This is the load-bearing idea.

## The manual trigger + selection

Artemis orchestrates (it owns the catalog and publishes jobs). A **manual** reprocess command
selects posts and a kind, then enqueues jobs:

```
   reprocess  --select "order:id"            (all)          --kind derivatives
   reprocess  --select "filetype:webm"       (just videos)  --kind derivatives
   reprocess  --select stale                 (out-of-date)  --kind derivatives
   reprocess  --select "id:1284"             (one)          --kind tags
                                        kind ∈ derivatives | tags | metadata
```

Selection reuses the search DSL. `kind` scopes *what* re-runs so, e.g., regenerating
thumbnails never touches your reviewed applied tags.

## The separate lane (uploads stay snappy)

```
   media.ingest     ← new uploads          (drained FIRST)
   media.reprocess  ← backfill jobs         (drained when ingest is idle / at lower weight)
```

Artemis enqueues reprocess jobs to `media.reprocess`; **Hephaestus and Argus pull `ingest`
before `reprocess`**, so a big backfill grinds in the background without starving a fresh
upload. (Simplest fallback if ever wanted: one FIFO queue — accepted trade of slower uploads
during a backfill.)

## Kinds, and what they touch

```
   derivatives  re-run Hephaestus → new thumbs/samples/transcodes/poster (overwrites);
                stamps derivativeSpecVersion. Does NOT change applied tags.
   tags         re-run Argus → new suggestions → back to the REVIEW QUEUE (re-flags
                needs-review); stamps taggerVersion. Never auto-applies.
   metadata     recompute dimensions/phash/etc. from the original.
```

## Idempotency & safety

- Derivatives are content-addressed → reprocessing overwrites byte-identical or updated
  files at the same paths; a redelivered job is harmless.
- A stale-selection reprocess is naturally **resumable** — re-running enqueues only the
  posts still below the current version.
- Kinds are isolated — reprocessing one kind never disturbs another's data.

## Closes two earlier loops

```
   #1 backup     back up ORIGINALS only → after a restore, reprocess --select all --kind
                 all regenerates every derivative → this is WHY backup is cheap
   #2 auto-tag   a better Argus model → reprocess --kind tags → new suggestions → review
```

## Out of scope

Automatic/scheduled reprocessing (manual only), a priority scheduler beyond "ingest before
reprocess," sub-"kind" granularity.
