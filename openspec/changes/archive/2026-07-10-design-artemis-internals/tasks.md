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
- [x] 4.4 (impl) Apollo gRPC client (streaming), HermesMQ publish/consume wiring
      <!-- Apollo gRPC client (adopt-lexicon-contracts) + concrete Hermes transport: pinned lexicon
           bumped 0.3.0→0.5.0 (+ `lexicon-hermes-grpc`); `HermesMediaJobPublisher` (Publish to
           media.process) + `HermesMediaResultConsumer.pollOnce` (Pull→decode→handler→Ack, ack only on
           success, poison/handler-failure left un-acked). Tested vs an in-process Hermes double.
           The runnable app `Main`/continuous poll-loop + durable `ProcessedJobs` are M9 cross-cutting. -->

## 5. Catalog API + media gateway

- [x] 5.1 (test) `GET /posts` (DSL, order, keyset cursor); `GET /posts/{id}` (read-your-writes); autocomplete
      <!-- read-your-writes `GET /posts/{id}` + `GET /pools/{id}` DONE (CatalogRoutesSpec); DSL list,
           keyset cursor, order, and autocomplete built + tested in the search-DSL milestone (M5,
           design-artemis-tag-search) and now SERVED by the assembled runtime (M9,
           assemble-runnable-service) — SearchRoutesSpec/SearchQueryIT + the 6.1 e2e cover it. -->
- [x] 5.2 (test) writes → commands (`POST /posts`, `PATCH …/tags`, favorite, score, pools); 404 on missing
- [x] 5.3 (test) media gateway streams Apollo derivatives over HTTP; `206` for video ranges
      <!-- `GET /media/{md5}/{variant}` behind a `MediaSource` port (`ApolloMediaSource` peels the
           getObject header/body); 200 full, 206 + Content-Range (byte-sliced across chunks), 416,
           404, Accept-Ranges — all route-tested Docker-free. -->
- [x] 5.4 (impl) pekko-http routes + media streaming; wire to entities + projections
      <!-- Write routes (5.2, wired to entities) + read-by-id + the media-streaming gateway (5.3) DONE.
           The DSL `GET /posts` list routes are now composed + served by M9's Main (search-first,
           wired to the projections via SearchService) — completing this task. -->

## 6. Integration

- [x] 6.1 (test) end-to-end (testcontainers): upload → pending → (mock Hephaestus) MediaProcessed → active → searchable
      <!-- Done in M9 (assemble-runnable-service) as `RunnableServiceE2EIT`: testcontainers Postgres +
           in-process Apollo/Hermes doubles drive the real assembled spine end to end. -->
- [x] 6.2 (docs) README: architecture, the Muses API contract, config
      <!-- README written in M9 (assemble-runnable-service 7.3): architecture, run/Docker, config,
           and endpoint reference. -->

<!-- 5.1/5.4/6.1/6.2 completed under the M9 assemble-runnable-service change, which binds and serves
     the surface these tasks describe. Ready to archive design-artemis-internals once M9 lands. -->
