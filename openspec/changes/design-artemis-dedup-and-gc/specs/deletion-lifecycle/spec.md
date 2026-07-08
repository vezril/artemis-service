# deletion-lifecycle

Soft-delete → retention → auto-purge → blob cleanup, plus a small orphan sweep for
failed-upload debris. Because storage is 1:1 (deduplicated-ingest), deletion is direct.

## ADDED Requirements

### Requirement: Reversible soft-delete that retains blobs

Deleting a post SHALL be a **soft-delete**: the post is hidden from normal browse/search and
is restorable, and its blobs (original + derivatives) are **retained**. A soft-deleted post
SHALL be excludable from all default queries.

#### Scenario: A soft-deleted post is hidden but recoverable
- **GIVEN** an active post
- **WHEN** it is deleted
- **THEN** it no longer appears in default searches, its blobs remain in Apollo, and it can be restored

#### Scenario: Edge case — restore returns it to active
- **GIVEN** a soft-deleted post
- **WHEN** it is restored
- **THEN** it is active again with its tags/relationships intact (blobs never left)

### Requirement: Retention-based auto-purge deletes blobs 1:1

Soft-deleted posts SHALL be **auto-purged** after a configurable retention window; purge is
permanent and SHALL delete exactly that post's `originals/<md5>` and `derivatives/<md5>/*`
from Apollo (safe because storage is 1:1 — no other post references them).

#### Scenario: A soft-deleted post purges after retention
- **GIVEN** a post soft-deleted longer than the retention window
- **WHEN** auto-purge runs
- **THEN** the post is permanently removed and its original + derivatives are deleted from Apollo

#### Scenario: Edge case — within-retention posts are not purged
- **GIVEN** a post soft-deleted more recently than the retention window
- **WHEN** auto-purge runs
- **THEN** it is left intact (still restorable) and its blobs remain

### Requirement: Grace-period orphan sweep for failed uploads

A sweep SHALL remove Apollo blobs that have **no referencing post** and are **older than a
grace window** — cleaning up failed/abandoned upload debris — while never touching blobs
within the grace window (protecting in-flight uploads) or any blob referenced by a post. The
sweep SHALL support a dry-run.

#### Scenario: Orphaned upload debris is swept
- **GIVEN** a blob written by a failed upload, with no post, older than the grace window
- **WHEN** the sweep runs
- **THEN** that blob is deleted

#### Scenario: Edge case — an in-flight upload's blob is protected
- **GIVEN** a freshly written blob (within the grace window) whose post is still being created
- **WHEN** the sweep runs
- **THEN** the blob is not deleted (the grace window protects it)
