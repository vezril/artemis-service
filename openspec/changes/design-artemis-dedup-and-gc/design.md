# Design: dedup + GC

One post per image, and a safe deletion lifecycle. Captured in explore mode; no implementation.

## The key move: uniqueness at the post layer → 1:1 → trivial GC

```
   dedup at the BLOB layer (shared)         uniqueness at the POST layer (1:1)
   post#12 ─┐                               post#12 ── md5 abc…  (only post for abc…)
   post#47 ─┴─ md5 abc…                     2nd upload of abc… → MERGE, no new post
   → GC needs ref-counting/mark-sweep       → purge post → delete its blob. Done.
```

Moving dedup earlier (never create the duplicate) makes storage 1:1 with posts, so deletion
is a direct blob delete — no reference-awareness needed.

## Ingest: exact-md5 dedup with merge

```
   upload → compute md5 (already done, streaming to Apollo)
        │  does a post with this md5 exist?
        ├─ live post exists   → MERGE into it (no new post):
        │                        union tags · add pool memberships · append source ·
        │                        keep existing rating (don't clobber)
        ├─ soft-deleted match → RESTORE it (you clearly want it back)
        └─ no match           → create the post normally
```

Two dedup layers, cleanly separated:
```
   EXACT (md5)   synchronous, at upload → uniqueness (merge). Makes GC 1:1.
   NEAR (phash)  async, post-processing → "possible duplicate" review notice (option B).
```

## Deletion lifecycle: soft → retention → purge → blob delete

```
   active → SOFT-DELETE (reversible; blob retained; hidden from browse/search)
          → after RETENTION window → AUTO-PURGE (permanent) → delete original + derivatives (1:1)
```

- Soft-delete keeps everything recoverable (an "oops").
- Auto-purge after a configurable retention (e.g. 30 days) makes cleanup hands-off but delayed
  enough to catch mistakes.
- Because it's 1:1, purge deletes exactly this post's `originals/<md5>` + `derivatives/<md5>/*`.

## Residual GC: failed-upload orphan sweep (small + safe)

Uniqueness doesn't cover one case: a **failed/abandoned upload** wrote a blob but the post was
never committed. A tiny sweep handles it:

```
   delete Apollo blobs that have NO referencing post AND are older than the GRACE window
   (grace protects in-flight uploads; dry-run first; manual/occasional)
```

Far smaller than mark-sweep — it's janitorial cleanup of upload debris, not deletion of
shared originals.

## Safety kit

```
   grace period        protects in-flight uploads from the sweep
   dry-run             the sweep reports before deleting
   retention           the auto-purge delay catches "oops, un-delete"
   BACKUP retention    must OUTLIVE purge → a wrong purge is still restorable from backup (#1)
   derivatives         rebuildable (#4) → even over-aggressive derivative deletion is low-stakes;
                       ORIGINALS are the precious thing the above layers protect
```

## Out of scope

Reference-counted GC · cross-post blob sharing (both eliminated by uniqueness).
