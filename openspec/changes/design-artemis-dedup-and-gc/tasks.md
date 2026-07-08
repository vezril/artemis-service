# Tasks: design-artemis-dedup-and-gc

TDD throughout. Builds on the Post aggregate + ingest from `design-artemis-internals`.

## 1. Deduplicated ingest

- [ ] 1.1 (test) md5 exists (live) → merge metadata (union tags, add pools, append source, keep rating); no new post
- [ ] 1.2 (test) md5 matches soft-deleted → restore + merge; md5 purged/absent → create fresh
- [ ] 1.3 (impl) md5-existence lookup on upload + merge/restore/create branch

## 2. Soft-delete

- [ ] 2.1 (test) soft-delete hides from default queries, retains blobs, is restorable
- [ ] 2.2 (impl) Delete/Restore events + projection excludes soft-deleted from defaults

## 3. Retention + auto-purge

- [ ] 3.1 (test) soft-deleted past retention purges + deletes original/derivatives (1:1); within-retention untouched
- [ ] 3.2 (impl) retention config + auto-purge job (Apollo delete by md5)

## 4. Orphan sweep

- [ ] 4.1 (test) blob with no post + past grace → swept; within-grace or referenced → protected
- [ ] 4.2 (impl) sweep: Apollo list − live md5 set, grace-guarded, dry-run

## 5. Safety

- [ ] 5.1 (docs) confirm backup retention OUTLIVES purge (a wrong purge is restorable — #1)
- [ ] 5.2 (test) integration: upload dup → merge · delete → soft → purge after retention → blobs gone
