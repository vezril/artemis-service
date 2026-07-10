# Change: assemble-runnable-service

> Wire the built-and-tested seams into a **runnable, deployable** Artemis service (roadmap M9,
> "cross-cutting"). Every capability — domain, persistence, projections, ingest (Apollo + Hermes),
> the media gateway, and the search DSL — exists behind clean seams but there is no `Main` that
> boots them. This change assembles them into a single process, ships a Docker image, and proves the
> whole media spine end-to-end.

## Why

Artemis is feature-complete but not *runnable*: entities are spawned ad-hoc in tests (no cluster
sharding → no single-writer-per-id in production), projections and the Hermes consume loop have no
scheduler, the tag graph is never loaded, and the HTTP routes (`CatalogRoutes`, `SearchRoutes`, the
media gateway) are never bound to a server. There is also no Docker image (so the `LOG_FORMAT=json`
default from `add-structured-logging` and any deployment are blocked). This change turns "all the
parts are built and green" into "the service starts, serves, and can be deployed", and delivers the
end-to-end test the internals change deferred (6.1).

## What Changes

- **service-runtime** (new): an application `Main`/guardian that forms a Pekko cluster, hosts the
  `Post`/`Pool` entities via **Cluster Sharding** (single writer per id), runs the read-model
  **Projections** (ShardedDaemonProcess) and the **Hermes consume loop** (`HermesMediaResultConsumer.pollOnce`
  on a stream/scheduler), loads and periodically **refreshes the `TagGraph` cache** (wiring the
  `PostEntity` supplier + the `SearchService` graph loader to it), and **binds the HTTP surface** —
  composing `health ~ SearchRoutes ~ CatalogRoutes ~ media-gateway` (search-first) on the configured
  port. A Postgres **readiness probe** gates startup. **BREAKING** for local dev only: the actor
  provider switches from `local` to `cluster`.
- **service-image** (new): a Docker image via `sbt-native-packager` — non-root user, `HEALTHCHECK`
  against `/health`, `EXPOSE`d ports, and `LOG_FORMAT=json` as the image env default — published to
  Docker Hub, versioned by `sbt-dynver` (matching the sibling services).
- **service-metrics** (new): a Prometheus `/metrics` endpoint (process + JVM + a few app counters).
- End-to-end: the internals **6.1** test (testcontainers) — upload → pending → (mock Hephaestus
  `MediaProcessed`) → active → searchable — now runnable against the assembled runtime.

## Capabilities

### New Capabilities
- `service-runtime`: the bootable application — cluster, sharded entities, projections + consume
  loop + graph cache, and the composed HTTP API, with a readiness-gated startup.
- `service-image`: a runnable, non-root, health-checked Docker image with the JSON-logging default,
  published to Docker Hub.
- `service-metrics`: a Prometheus metrics endpoint for scraping.

### Modified Capabilities
<!-- None — this composes existing capabilities into a running process; their requirements are
     unchanged. The internals catalog-api 5.1/5.4 and 6.1 become satisfiable once the routes are
     bound and the pipeline runs, but their spec requirements do not change. -->

## Impact

- **Affected code:** `build.sbt` (native-packager + Docker, cluster/management deps, buildinfo),
  new `Main` + wiring (`PostSharding`/`PoolSharding`, projection + consume-loop startup, the graph
  cache, HTTP bind), `application.conf` (provider → cluster) + a new `cluster.conf`, a health/metrics
  route, and an e2e integration test.
- **Depends on:** everything built this session (domain, persistence, projections, ingest, media
  gateway, search) plus running Apollo, HermesMQ, and Postgres at runtime (Hephaestus mocked in the
  e2e test).
- **Unblocks:** `design-artemis-internals` 6.1 (e2e) and finalizes 5.1/5.4 (the search endpoints are
  now actually served); the Codex k8s deploy (once the image is published).
- **Out of scope:** the Helm chart / k8s manifests (the Codex/GitOps side, once an image exists),
  multi-user/auth, and the durable `ProcessedJobs` store + tag-graph invalidation-on-write (a
  periodic-refresh cache is sufficient at personal scale; note the staleness window).
