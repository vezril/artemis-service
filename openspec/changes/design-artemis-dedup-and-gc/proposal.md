# Change: design-artemis-dedup-and-gc

> **Design capture (explore mode).** Enforcing one-post-per-image (md5 uniqueness) so blob
> storage is 1:1 with posts, plus the soft-delete → retention → purge → blob-cleanup
> lifecycle. Supersedes the earlier mark-sweep GC idea (simpler + safer). Builds on
> `design-artemis-internals`. No code implemented.

## Why

Content-addressed storage dedups identical images to one blob. If two posts could share a
blob, deleting one risks corrupting the other — forcing reference-aware GC. **Enforcing md5
uniqueness at the post layer** (what Danbooru does) makes blob↔post **1:1**, so deletion is
trivial (purge a post → delete its blob) and the scary "am I deleting an original two posts
need?" problem disappears. What's left is only janitorial cleanup of failed-upload debris.

## Decisions carried in from exploration

| Decision | Choice |
|----------|--------|
| Duplicate upload | **Merge** — a re-upload of an existing md5 does not create a new post; it **merges the new metadata** (union tags, add pool memberships, append source) into the existing post |
| Soft-deleted match | uploading an md5 that matches a **soft-deleted** post **restores** it |
| Deletion | **soft-delete** (reversible, keeps the blob, hidden from browse/search) |
| Purge | **retention + auto-purge** — soft-deleted posts auto-purge after a configurable retention window; purge permanently deletes the post's original + derivatives (1:1) |
| Residual GC | a **grace-period orphan sweep** for failed-upload debris only (blobs with no post, older than the grace window) — much smaller than mark-sweep |
| Safety | dry-run for the sweep; backup retention must outlive purge (a wrong purge stays recoverable) |

## What Changes

- **deduplicated-ingest** (new): on upload, compute md5; a live post with that md5 → **merge**
  metadata (no new post); a soft-deleted match → **restore**; otherwise create. Keeps blob↔post 1:1.
- **deletion-lifecycle** (new): soft-delete (reversible, retains blob); retention-based
  auto-purge that (1:1) deletes the post's blobs; a grace-period orphan sweep for
  failed-upload debris.

## Impact

- Affected specs: `deduplicated-ingest`, `deletion-lifecycle` are **ADDED**.
- Modifies ingest (`design-artemis-internals`): the upload path gains the md5-existence check
  before creating a post. Two dedup layers now: **exact md5 = merge (synchronous, here)** vs
  **near-dup phash = review warning (async, `design-artemis-auto-tagging`)** — complementary.
- Relates to: `#1 backup` (retention window is the safety net for a bad purge), `#4
  reprocessing` (derivatives rebuildable → low-stakes), `#5 find-similar` (near-dup layer).
- Content-addressing stays — it provides the md5 for the uniqueness check, integrity, and
  immutable-URL caching.
- Out of scope: reference-counted GC (rejected for mark/uniqueness), cross-post blob sharing
  (eliminated by uniqueness).
