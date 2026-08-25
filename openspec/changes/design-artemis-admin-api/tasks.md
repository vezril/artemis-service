## 1. Internal seams (compose existing capabilities)

- [ ] 1.1 Add a single-post purge method to `PurgeService` (e.g. `purgeNow(id): Future[Option[Int]]`)
      that reuses the batch loop's per-target step: resolve the post's blob keys (original +
      `derivativeObjectKeys`), delete them best-effort, then issue `Purge` (atomic still-`Deleted`
      confirmation). Factor the shared step so batch and single-post callers use one code path.
- [ ] 1.2 Enrich `OrphanSweepService.sweep` to report the scanned/listed count alongside the orphan
      plan (e.g. return a small `SweepResult(scanned, orphans)`), leaving the pure `OrphanSweep.plan`
      untouched.

## 2. Deletion HTTP surface (`admin-deletion`)

- [ ] 2.1 Add `AdminDeletionRoutes` (injected `postFor` + the `PurgeService.purgeNow` seam) with
      spray-json request/response codecs, following the `CatalogRoutes`/`ReprocessRoutes` house style.
- [ ] 2.2 Implement `DELETE /posts/{id}` → issue `Delete(now())` via `onExecute`, answer
      `200 {id, status:"deleted"}`, unknown post → 404 through `HttpErrors`.
- [ ] 2.3 Implement `POST /posts/{id}/restore` → issue `Restore(now())`, answer
      `200 {id, status:"active"}`, mapped errors via `HttpErrors`.
- [ ] 2.4 Implement `POST /posts/{id}/purge` → call the single-post purge seam, answer
      `200 {id, purged, blobsDeleted}`; report `purged:false` when the post is no longer `Deleted`;
      idempotent no-op (`blobsDeleted:0`) on an already-purged post.
- [ ] 2.5 Ensure these routes compose AFTER search but consistently with the existing `/posts`
      segment routing so `/posts/{id}/purge` and `/restore` are claimed correctly.

## 3. GC HTTP surface (`admin-gc`)

- [ ] 3.1 Add `AdminGcRoutes` with injected `(dryRun: Boolean) => Future[SweepResult]` and
      `() => Future[Int]` seams plus JSON codecs.
- [ ] 3.2 Implement `POST /admin/gc/orphan-sweep` accepting `{dryRun}` (default true) →
      `200 {scanned, orphans, deleted}` (`deleted:0` on dry-run).
- [ ] 3.3 Implement `POST /admin/gc/purge-deleted` → call `PurgeService.purgeDue(Instant.now())` →
      `200 {purged}`.

## 4. Wiring

- [ ] 4.1 Construct `OrphanSweepService` in `Main` (currently unwired) and inject it into
      `AdminGcRoutes`.
- [ ] 4.2 Inject the existing `PurgeService` (purge pass + new single-post purge) into the admin
      routes; the scheduled `startPurgeLoop` remains unchanged.
- [ ] 4.3 Compose `adminDeletionRoutes.routes` and `adminGcRoutes.routes` into the route tree in
      `Main` alongside `reprocessRoutes`.

## 5. Tests

- [ ] 5.1 Route tests for `admin-deletion` with fakes: soft-delete happy path + 404; restore happy
      path + 404; purge happy path (blob count), rejected-when-active, idempotent-no-op.
- [ ] 5.2 Route tests for `admin-gc` with fakes: orphan-sweep dry-run vs real (counts + protected
      referenced blob); purge-deleted past-retention vs within-retention vs repeat.
- [ ] 5.3 `PurgeService.purgeNow` unit tests (fake `BlobStore` + entity): blobs deleted before
      `Purge`, still-`Deleted` confirmation prevents purging a restored post, idempotency.

## 6. Validate

- [ ] 6.1 `openspec validate design-artemis-admin-api --strict` reports valid.
