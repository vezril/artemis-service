## Context

Artemis is a single-user, self-hosted, event-sourced media service (Scala 3 + Pekko). Its deletion
lifecycle and GC behaviors are fully implemented internally but only partially reachable:

- **Deletion lifecycle** (`openspec/specs/deletion-lifecycle`): the post aggregate already has
  `Delete`, `Restore`, and `Purge` commands (`core/.../domain/PostCommand.scala`). `Delete`/`Restore`
  are driven today only by the ingest path (a re-uploaded md5 restores a soft-deleted post — see
  `deduplicated-ingest`); `Purge` is driven only by the scheduled retention job.
- **Retention purge** (`server/.../gc/PurgeService.scala`): `purgeDue(now): Future[Int]` finds
  soft-deleted posts past the retention window, deletes each one's blobs 1:1, and purges the row.
  It is driven by `startPurgeLoop` in `Main` on a timer.
- **Orphan sweep** (`server/.../gc/OrphanSweepService.scala`): `sweep(dryRun): Future[Seq[BlobRef]]`
  lists `originals/`, subtracts every referenced md5, and deletes the leftovers. It is deliberately
  unscheduled and **not wired into `Main` at all** today.

None of these have an HTTP surface. The existing HTTP layer
(`server/.../http/*.scala`) establishes the house style this design follows:

- Route classes take injected functions (e.g. `ReprocessRoutes(reprocess: (String, ReprocessKind) => Future[Int])`)
  so they unit-test without a DB/Apollo, and are composed in `Main` (`... ~ reprocessRoutes.routes`).
- spray-json codecs in a companion `object`; a small request/response case class per endpoint.
- The shared error body is `ErrorResponse(error: String)` → `{"error": "..."}`; failures map to
  status via the single exhaustive `HttpErrors.statusFor` (`PostNotFound` → 404, conflicts → 409,
  invalid* → 400, else 500). The `onExecute` helper asks the aggregate and completes `200` or the
  mapped error.
- `RequestTracing.withCorrelationId` mints and echoes `X-Correlation-Id` on every response and
  ignores any client-supplied value (anti-injection). There is **no authentication** anywhere.

## Goals / Non-Goals

**Goals:**
- Expose the deletion lifecycle per-post over HTTP: soft-delete, restore, immediate hard-purge.
- Expose GC on demand: trigger the orphan sweep (with dry-run) and one retention purge pass.
- Every triggerable admin action reports a concrete result count and is safe to call repeatedly
  (idempotent where the underlying op is).
- Match the existing route conventions exactly (codecs, shared error body, correlation id, injected
  seams, `Main` composition) so the admin routes are testable with fakes.

**Non-Goals:**
- **No dedup admin surface.** Two distinct dedup mechanisms exist and *neither* is a triggerable or
  queryable batch operation:
  - *md5 dedup* (`deduplicated-ingest`) is an **inline merge at upload** — a duplicate md5 merges
    into (or restores) the existing post. There is no batch "dedup pass" to trigger; it only ever
    happens on the ingest path.
  - *perceptual duplicate flagging* (`FlagPossibleDuplicate` → the `duplicate_of` column) is set
    **inline at processing time** by comparing a just-activated post's phash against active phashes.
    `ReadModelRepository` exposes only a write (`setDuplicateOf`); there is **no cluster-inspection
    query**, and the search DSL exposes **no `dup:` filter**. The only read of `duplicate_of` is the
    per-post field already returned by `GET /posts/{id}`.
  Designing "inspect duplicate clusters" or "trigger a dedup pass" endpoints would require inventing
  internal capabilities that do not exist, so dedup is scoped out. If cluster browsing is wanted
  later, the honest prerequisite is a new read-model query (and possibly a `dup:` DSL filter), which
  belongs in its own change.
- **No last-run status endpoint.** The service persists no last-run record for GC jobs (only
  `service-metrics` counters exist). Exposing "last-run status" would require new state; out of scope.
- **No authentication / no `/admin` auth boundary.** Consistent with the single-user service; the
  `/admin` path prefix is organizational only.
- No changes to domain, persistence, projection, or existing specs — this change only triggers and
  observes what already exists.

## Decisions

### 1. Deletion endpoints live on the `/posts/{id}` resource; GC lives under `/admin/gc`

Per-post lifecycle actions are state transitions on an existing resource, so they follow the
established `/posts/{id}/<action>` pattern (mirroring `POST /posts/{id}/favorite`,
`POST /posts/{id}/score`):

- `DELETE /posts/{id}` — soft-delete (HTTP `DELETE` matches the reversible-hide semantics).
- `POST /posts/{id}/restore` — restore (an action sub-path, like `favorite`).
- `POST /posts/{id}/purge` — immediate hard-purge (a distinct, irreversible action sub-path; kept
  separate from `DELETE` so the destructive step is never the default verb on the resource).

Service-wide janitorial actions are not tied to one post, so they group under `/admin/gc/*`
(`orphan-sweep`, `purge-deleted`). *Alternative considered:* a flat `/gc/*` (closer to the flat
`/reprocess`). Chose `/admin/gc` to clearly demarcate operational endpoints from the catalog API;
since there is no auth, the prefix is purely for grouping.

### 2. Soft-delete and restore go straight through the aggregate via the existing `onExecute` seam

`DELETE /posts/{id}` issues `Delete(now())` and `POST /posts/{id}/restore` issues `Restore(now())`
against `postFor(id)` exactly as the catalog routes issue their commands — reusing `HttpErrors` for
status mapping (unknown post → `PostNotFound` → 404). The only enrichment over the catalog routes'
bare `200` is a small `{id, status}` body so each admin action reports its resulting state.

### 3. Hard-purge composes the *existing* purge steps for a single nominated post

The retention job's ordering contract — delete blobs 1:1, then `Purge` (which atomically confirms
still-`Deleted`) — must be preserved for a single-post purge so an active/restored post is never
purged and its blobs never leak. This requires a small single-target method on `PurgeService`
(e.g. `purgeNow(id): Future[Option[Int]]`) that resolves the post's blob keys (the read model
already builds these — `PurgeTarget.blobKeys` from the original + `derivativeObjectKeys`), deletes
them best-effort, then issues `Purge`. This is **composition of existing capabilities**
(`Purge` command + `BlobStore.delete` + the read model's recorded keys), not a new behavior. The
route reports `{purged, blobsDeleted}`. *Alternative considered:* have the route issue a raw `Purge`
command only — rejected because that purges the row while **leaking the blobs** (reclaimable only by
a later orphan sweep), violating the 1:1 deletion guarantee.

### 4. GC endpoints wrap the existing service methods directly, injected as functions

- `POST /admin/gc/orphan-sweep` wraps `OrphanSweepService.sweep(dryRun)`; the route maps the returned
  `Seq[BlobRef]` to `{scanned, orphans, deleted}`. (`scanned` needs the listed-count; the sweep can
  return it or the route can obtain it from the same list — a minor signature enrichment, still no
  new behavior.) This finally wires `OrphanSweepService` into `Main`.
- `POST /admin/gc/purge-deleted` wraps `PurgeService.purgeDue(Instant.now())` → `{purged}`.

Both are injected as plain functions (`(dryRun: Boolean) => Future[SweepResult]`,
`() => Future[Int]`) so the routes test with fakes, matching `ReprocessRoutes`.

### 5. dryRun defaults to true for the orphan sweep

The internal sweep is documented as "dry-run first" janitorial tooling. The endpoint keeps that
safety: an absent/true `dryRun` computes and reports the plan without deleting. A real sweep requires
an explicit `{"dryRun": false}`.

## Risks / Trade-offs

- **Destructive endpoints with no auth** → single-user, localhost/LAN-bound service; the whole API is
  already unauthenticated. Mitigation: dry-run default on the sweep, the `Purge` still-`Deleted`
  confirmation, and the `/posts/{id}/purge` action-sub-path keeping the destructive verb off the
  bare resource. Deploy behind the network boundary as the rest of the service is.
- **Single-post purge is a new (small) `PurgeService` method** → risk of drifting from the batch
  path's safety ordering. Mitigation: implement it by factoring the batch loop's per-target
  `purge-then-delete-blobs` step so both call sites share one code path.
- **`scanned` count requires a minor sweep signature change** → the sweep currently returns only the
  orphan plan. Mitigation: return the listed count alongside the plan (or a small `SweepResult`),
  keeping the pure `OrphanSweep.plan` untouched.
- **Concurrent purge vs. restore race** → already handled by the aggregate's atomic still-`Deleted`
  check; the endpoint inherits it and reports `purged: false` when the post is no longer deleted.

## Migration Plan

Additive only — new routes composed into the existing tree, no existing endpoint or spec changes.
Rollback is removing the route composition lines in `Main`. No data migration. The orphan-sweep and
single-post-purge wiring are new constructions in `Main`; the scheduled retention loop is unchanged.

## Open Questions

- Should `POST /admin/gc/purge-deleted` accept an optional `olderThan`/override to purge on a
  different cutoff than the configured retention, or strictly use the configured window? (This design
  assumes strictly the configured window, to keep it a pure "run the existing pass now" trigger.)
- Should the orphan-sweep response optionally include the orphan keys (not just counts) for operator
  eyeballing before a real run, or keep the body counts-only? (This design specifies counts-only;
  keys can be added later without breaking the shape.)
