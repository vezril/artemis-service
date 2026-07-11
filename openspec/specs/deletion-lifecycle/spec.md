# deletion-lifecycle Specification

## Purpose
Soft-delete → retention → auto-purge → blob cleanup, plus a small orphan sweep for failed-upload
debris. Because storage is 1:1 (deduplicated-ingest), deletion is a direct blob delete.

## Requirements
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
permanent and SHALL delete exactly that post's original + derivative blobs from Apollo (safe
because storage is 1:1 — no other post references them). The purge SHALL confirm the post is
still soft-deleted (atomically, in the aggregate) BEFORE deleting any blob, so a post restored
in the meantime keeps its blobs.

#### Scenario: A soft-deleted post purges after retention
- **GIVEN** a post soft-deleted longer than the retention window
- **WHEN** auto-purge runs
- **THEN** the post is permanently removed and its original + derivatives are deleted from Apollo

#### Scenario: Edge case — within-retention posts are not purged
- **GIVEN** a post soft-deleted more recently than the retention window
- **WHEN** auto-purge runs
- **THEN** it is left intact (still restorable) and its blobs remain

#### Scenario: Edge case — a post restored during the retention pass keeps its blobs
- **GIVEN** a soft-deleted post that is restored after the purge work-list is read but before it is purged
- **WHEN** auto-purge tries to purge it
- **THEN** the purge is rejected (the post is active again) and its blobs are NOT deleted

### Requirement: Orphan sweep for failed uploads

A sweep SHALL remove Apollo `originals/` blobs that have **no referencing post** — cleaning up
failed/abandoned upload debris — while never touching any blob referenced by a post. The
referencing set SHALL include in-flight (still-`pending`) uploads, so a blob whose post is
already being created is protected. The sweep SHALL support a dry-run. (Apollo exposes no blob
creation time, so protection is by post-reference, not blob age.)

#### Scenario: Orphaned upload debris is swept
- **GIVEN** an `originals/` blob with no post of any status referencing its md5
- **WHEN** the sweep runs
- **THEN** that blob is deleted

#### Scenario: Edge case — an in-flight upload's blob is protected
- **GIVEN** a freshly written blob whose post is still being created (a pending post already references its md5)
- **WHEN** the sweep runs
- **THEN** the blob is not deleted (a referenced blob is never debris)
