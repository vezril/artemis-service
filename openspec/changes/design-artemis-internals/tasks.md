# Tasks: design-artemis-internals

TDD throughout (Red → Green → Refactor), matching the sibling services. Dependency order:
pure domain → projections → ingest → API → media gateway.

## 1. Post aggregate (pure domain)

- [x] 1.1 (test) `Post` state + lifecycle (pending→active→deleted); commands rejected on deleted
- [x] 1.2 (impl) Command/event ADTs + `decide`/`evolve`
- [x] 1.3 (test) `ChangeTags` canonicalizes (alias→implication→dedup) before `TagsChanged`; history reconstructable
- [x] 1.4 (test) rating/parent/favorite/score as events; favorite idempotent; invalid rating rejected
- [x] 1.5 (impl) `EventSourcedBehavior` wiring (Pekko Persistence journal)

## 2. Pool aggregate

- [x] 2.1 (test) create/add/remove/reorder ordered membership; add-dup no-op; remove-absent rejected
- [x] 2.2 (impl) `Pool` entity + events

## 3. Read-model projections

- [x] 3.1 (test) `posts` projection (tags[], scalars, status, media refs) + GIN/btree
- [x] 3.2 (test) `tags` projection (post_count) + pg_trgm; `pools`/`pool_posts`
- [x] 3.3 (test) rebuild-from-journal reproduces state; replay is idempotent (upsert by id)
- [x] 3.4 (impl) Pekko Projections + read-table DDL

## 4. Ingest & processing

- [x] 4.1 (test) upload: md5-on-stream → Apollo, pending Post, publish `ProcessMediaJob`; checksum-mismatch aborts
      <!-- `UploadService` behind `ObjectUploader`/`MediaJobPublisher` ports; abort-ordering verified. -->
- [x] 4.2 (test) consume `MediaProcessed`→active / `MediaFailed`→failed; idempotent per `jobId`
      <!-- `MediaResultHandler` + `failed`-state domain extension (MarkFailed→ProcessingFailed→Failed,
           idempotent in the domain); dedup via `ProcessedJobs` port is an optimization on top. -->
- [x] 4.3 (test) post-processing phash dup flag (Hamming threshold); unique → no flag
      <!-- Event-sourced: FlagPossibleDuplicate→PossibleDuplicateFlagged→`duplicate_of` projection
           column (rebuildable). Pure per-byte `PerceptualHash.hamming`; `NearDuplicates` port +
           `ReadModelNearDuplicates` (closest match ≤ threshold, self-excluded); wired post-activation
           in the consumer as a best-effort warning (a detection outage never blocks ingest). -->
- [~] 4.4 (impl) Apollo gRPC client (streaming), HermesMQ publish/consume wiring
      <!-- Apollo gRPC client DONE (adopt-lexicon-contracts: `ApolloObjectClient`/`ApolloObjectUploader`).
           Publish/consume LOGIC built behind ports (`MediaJobPublisher`/`ProcessedJobs`). The concrete
           `lexicon-hermes-grpc` adapter is deferred until that contract cuts a clean release. -->

## 5. Catalog API + media gateway

- [~] 5.1 (test) `GET /posts` (DSL, order, keyset cursor); `GET /posts/{id}` (read-your-writes); autocomplete
      <!-- read-your-writes `GET /posts/{id}` + `GET /pools/{id}` DONE (CatalogRoutesSpec); DSL list,
           keyset cursor, order, and autocomplete DEFERRED to the search-DSL milestone (M5, in the
           design-artemis-tag-search change) since they need the DSL→SQL compiler. -->
- [x] 5.2 (test) writes → commands (`POST /posts`, `PATCH …/tags`, favorite, score, pools); 404 on missing
- [ ] 5.3 (test) media gateway streams Apollo derivatives over HTTP; `206` for video ranges
- [ ] 5.4 (impl) pekko-http routes + media streaming; wire to entities + projections

## 6. Integration

- [ ] 6.1 (test) end-to-end (testcontainers): upload → pending → (mock Hephaestus) MediaProcessed → active → searchable
- [ ] 6.2 (docs) README: architecture, the Muses API contract, config
