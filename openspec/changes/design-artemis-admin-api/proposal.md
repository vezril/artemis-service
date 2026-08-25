## Why

Artemis already implements a full deletion lifecycle (soft-delete → retention → auto-purge) and a
failed-upload orphan sweep, but several of these operational capabilities have **no HTTP surface**:
a post can only be soft-deleted/restored/purged by the internal ingest and retention machinery, the
orphan sweep (`OrphanSweepService`) is not even wired into `Main`, and the retention purge pass can
only fire on its schedule. For a single-user, self-hosted deployment the operator needs an on-demand
way to drive these housekeeping actions and to soft-delete/restore/purge individual posts without
re-uploading or waiting for the retention timer. This change designs a small admin HTTP surface over
the operational capabilities that already exist internally.

## What Changes

- Add an HTTP **deletion-lifecycle** surface on the existing post resource:
  - `DELETE /posts/{id}` — reversible soft-delete (hide, retain blobs).
  - `POST /posts/{id}/restore` — restore a soft-deleted post to active.
  - `POST /posts/{id}/purge` — immediate hard-purge of a soft-deleted post (delete its blobs 1:1,
    then purge the row), bypassing the retention timer, reporting the blobs deleted.
- Add an HTTP **GC/janitorial** surface under `/admin/gc`:
  - `POST /admin/gc/orphan-sweep` — run the failed-upload orphan sweep on demand, with a `dryRun`
    flag, reporting what was scanned/planned/deleted (this finally wires `OrphanSweepService`).
  - `POST /admin/gc/purge-deleted` — run one retention purge pass immediately, reporting the count
    of posts purged.
- All admin endpoints reuse the house route conventions: spray-json codecs, the shared
  `{"error": "..."}` body via `HttpErrors` status mapping, and the server-minted `X-Correlation-Id`
  already echoed on every response. No authentication is added (single-user, matching the rest of
  the service).
- Dedup admin endpoints are **explicitly out of scope** — see Non-goals in `design.md`.

## Capabilities

### New Capabilities
- `admin-deletion`: HTTP endpoints to soft-delete, restore, and immediately hard-purge an
  individual post, exposing the existing `Delete`/`Restore`/`Purge` post-aggregate commands and the
  blob-delete step of the retention purge.
- `admin-gc`: HTTP endpoints to trigger the orphan sweep (with dry-run) and an on-demand retention
  purge pass, exposing `OrphanSweepService.sweep` and `PurgeService.purgeDue` and reporting concrete
  result counts.

### Modified Capabilities
<!-- None. This change only ADDS an HTTP surface; the deletion-lifecycle and deduplicated-ingest
     behaviors themselves are unchanged. -->

## Impact

- **New code (implementation, not part of this design change):** two new route classes under
  `server/src/main/scala/me/cference/artemis/http/` (e.g. `AdminDeletionRoutes`, `AdminGcRoutes`)
  plus their JSON codecs, composed into the route tree in `Main.scala` alongside `reprocessRoutes`.
- **Wiring:** `OrphanSweepService` (currently unwired) and a new single-post purge path on
  `PurgeService` must be constructed in `Main` and injected into the admin routes.
- **No changes** to the domain, persistence, projection, or existing specs — the admin API only
  triggers/observes capabilities that already exist.
- **Ops:** the purge and orphan-sweep endpoints permanently delete blobs; they inherit the existing
  safety contracts (purge confirms still-`Deleted` before deleting; orphan sweep protects any
  referenced md5 including in-flight `pending` posts, and supports dry-run).
