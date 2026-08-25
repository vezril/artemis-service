# admin-deletion Specification

## Purpose
TBD - created by archiving change design-artemis-admin-api. Update Purpose after archive.
## Requirements
### Requirement: Soft-delete a post over HTTP

`DELETE /posts/{id}` SHALL soft-delete the identified post by issuing the `Delete` post-aggregate
command, hiding it from default browse/search while retaining its blobs, and SHALL answer `200` with
`{"id": "<id>", "status": "deleted"}`. A request for an unknown post SHALL map to `404` with the
shared `{"error": "..."}` body via the `HttpErrors` mapping (`PostNotFound` → 404). The response
carries the server-minted `X-Correlation-Id` like every other route.

#### Scenario: Soft-deleting an active post

- **WHEN** the operator sends `DELETE /posts/1284` for an active post
- **THEN** the post is soft-deleted (hidden from default queries, blobs retained) and the response is
  `200` with body `{"id": "1284", "status": "deleted"}`

#### Scenario: Edge case — deleting an unknown post is a 404

- **WHEN** the operator sends `DELETE /posts/9999` for a post that was never created (or already purged)
- **THEN** the response is `404` with body `{"error": "post not found"}`

### Requirement: Restore a soft-deleted post over HTTP

`POST /posts/{id}/restore` SHALL restore a soft-deleted post to active by issuing the `Restore`
post-aggregate command, returning its tags and relationships intact, and SHALL answer `200` with
`{"id": "<id>", "status": "active"}`. Restoring a post that is not soft-deleted SHALL surface the
aggregate's typed rejection through the shared error mapping, and an unknown post SHALL be `404`.

#### Scenario: Restoring a soft-deleted post

- **WHEN** the operator sends `POST /posts/1284/restore` for a soft-deleted post
- **THEN** the post becomes active again with its tags and relationships intact and the response is
  `200` with body `{"id": "1284", "status": "active"}`

#### Scenario: Edge case — restoring an unknown post is a 404

- **WHEN** the operator sends `POST /posts/9999/restore` for a post that does not exist
- **THEN** the response is `404` with body `{"error": "post not found"}`

### Requirement: Immediate hard-purge of a soft-deleted post over HTTP

`POST /posts/{id}/purge` SHALL immediately and permanently purge a soft-deleted post, bypassing the
retention window, by first deleting that post's original and derivative blobs 1:1 and then issuing
the `Purge` command, and SHALL answer `200` with `{"id": "<id>", "purged": true, "blobsDeleted": <n>}`
reporting the concrete number of blobs removed. The operation MUST preserve the existing purge safety
contract: the aggregate atomically confirms the post is still `Deleted` before any irreversible step,
so a post restored in the meantime is not purged. Purge SHALL be idempotent — purging an
already-purged post is an accepted no-op that reports `blobsDeleted: 0`.

#### Scenario: Purging a soft-deleted post deletes its blobs and the row

- **WHEN** the operator sends `POST /posts/1284/purge` for a post that is soft-deleted with one
  original and two derivative blobs
- **THEN** its three blobs are deleted from Apollo, the post row is purged, and the response is `200`
  with body `{"id": "1284", "purged": true, "blobsDeleted": 3}`

#### Scenario: Edge case — purge is rejected for a post that is not soft-deleted

- **WHEN** the operator sends `POST /posts/1284/purge` for a post that is currently active (never
  deleted, or restored since it was deleted)
- **THEN** no blobs are deleted, the post is left intact, and the response reports the purge did not
  occur (`purged: false`) rather than removing an active post

#### Scenario: Edge case — purging an already-purged post is an idempotent no-op

- **WHEN** the operator sends `POST /posts/1284/purge` twice
- **THEN** the second call deletes no blobs and responds `200` with body
  `{"id": "1284", "purged": true, "blobsDeleted": 0}`

