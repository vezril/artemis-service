# admin-gc Specification

## Purpose
TBD - created by archiving change design-artemis-admin-api. Update Purpose after archive.
## Requirements
### Requirement: Trigger the orphan sweep over HTTP

`POST /admin/gc/orphan-sweep` SHALL run the failed-upload orphan sweep on demand by invoking
`OrphanSweepService.sweep`, and SHALL answer `200` with `{"scanned": <n>, "orphans": <k>, "deleted": <d>}`
reporting the concrete counts: `scanned` originals listed, `orphans` planned for deletion (blobs whose
md5 is referenced by no post of any status, including in-flight `pending` uploads), and `deleted`
actually removed. The request body MUST accept `{"dryRun": <bool>}`; when `dryRun` is true (the safe
default) the sweep computes and reports the plan with `deleted: 0` and removes nothing. The sweep MUST
never touch a referenced blob, and MUST be safe to call repeatedly — a real sweep followed by another
finds no new orphans.

#### Scenario: Dry-run reports the plan without deleting

- **WHEN** the operator sends `POST /admin/gc/orphan-sweep` with body `{"dryRun": true}` and three
  originals exist, one of which no post references
- **THEN** the response is `200` with body `{"scanned": 3, "orphans": 1, "deleted": 0}` and no blob is
  deleted

#### Scenario: A real sweep deletes only unreferenced debris

- **WHEN** the operator sends `POST /admin/gc/orphan-sweep` with body `{"dryRun": false}` and one
  `originals/` blob has no referencing post while an in-flight `pending` upload references another
- **THEN** the unreferenced blob is deleted, the `pending` upload's blob is protected, and the response
  is `200` with body `{"scanned": 2, "orphans": 1, "deleted": 1}`

#### Scenario: Edge case — repeating a real sweep is a no-op

- **WHEN** the operator sends `POST /admin/gc/orphan-sweep` with `{"dryRun": false}` a second time
  immediately after a successful sweep
- **THEN** the response reports `orphans: 0` and `deleted: 0`

### Requirement: Trigger an on-demand retention purge pass over HTTP

`POST /admin/gc/purge-deleted` SHALL run one retention-based auto-purge pass immediately by invoking
`PurgeService.purgeDue(now)`, and SHALL answer `200` with `{"purged": <n>}` reporting the concrete
number of posts permanently purged in the pass. The pass MUST remain retention-gated — only posts
soft-deleted longer than the configured retention window are purged, and within-retention posts are
left intact — so the endpoint triggers the timing of an existing pass, never a bypass of retention.
The pass MUST be safe to call repeatedly: back-to-back calls purge only what has since become due.

#### Scenario: Purging posts past the retention window

- **WHEN** the operator sends `POST /admin/gc/purge-deleted` and two posts have been soft-deleted
  longer than the retention window
- **THEN** those two posts are permanently purged with their blobs deleted 1:1 and the response is
  `200` with body `{"purged": 2}`

#### Scenario: Edge case — within-retention posts are not purged

- **WHEN** the operator sends `POST /admin/gc/purge-deleted` and the only soft-deleted post is still
  within the retention window
- **THEN** nothing is purged and the response is `200` with body `{"purged": 0}`

#### Scenario: Edge case — a repeated pass purges only newly-due posts

- **WHEN** the operator sends `POST /admin/gc/purge-deleted` twice in succession
- **THEN** the second call purges only posts that became due since the first and otherwise responds
  `{"purged": 0}`

