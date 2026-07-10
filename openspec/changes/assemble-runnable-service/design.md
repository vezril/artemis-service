# Design: assemble-runnable-service

## Context

Every Artemis capability is built and green behind a seam, but nothing composes them into a process:
- `PostEntity`/`PoolEntity` are spawned per-id in tests; production needs **one writer per id** →
  Cluster Sharding (a bare `spawn` per request would create competing writers on the same journal).
- The `Post`/`Pool` **Projections** and the **Hermes consume loop** (`HermesMediaResultConsumer.pollOnce`)
  are pure/testable steps with no runtime driver.
- The `TagGraph` supplier on `PostEntity` and in `SearchService` defaults to empty; nothing loads it.
- `CatalogRoutes`/`SearchRoutes`/the media gateway and `HealthRoutes` are never bound to an HTTP server.
- There is no Docker image, so `LOG_FORMAT=json` and deployment are blocked.

The sibling services (Apollo/HermesMQ) already solve the cluster + sharding + Docker + health shape;
this change mirrors them. Read `/Users/cference/Code/apollo-storage` for the reference: `Main.scala`,
`persistence/BucketSharding.scala`, `resources/cluster.conf`, `http/HttpServer.scala`,
`persistence/PersistenceReadiness.scala`, and the `build.sbt` Docker/native-packager block.

## Goals / Non-Goals

**Goals:**
- A single `Main` that boots a cluster-of-one (scales out unchanged), hosts sharded entities, runs
  projections + the consume loop + a refreshing tag-graph cache, and serves the composed HTTP API.
- Startup is **readiness-gated** on Postgres; `/health` flips `DOWN` before unbind on shutdown.
- A **runnable Docker image** (non-root, healthcheck, `LOG_FORMAT=json`, dynver-versioned) → Docker Hub.
- A **Prometheus** `/metrics` endpoint.
- The internals **6.1 e2e** proven against the assembled runtime.

**Non-Goals:** the Helm chart / k8s manifests (Codex/GitOps side, once the image is published),
multi-user/auth, a durable `ProcessedJobs`, and write-path tag-graph invalidation (periodic refresh
suffices at personal scale).

## Decisions

- **Cluster Sharding, cluster-of-one by default** (mirror `BucketSharding`). `PostSharding`/`PoolSharding`
  define an `EntityTypeKey` and `init(system)` hosting `PostEntity(...)`/`PoolEntity(...)`; the HTTP
  layer gets `EntityRef`s via `entityRefFor`. `application.conf` provider → `cluster`; a new
  `cluster.conf` (split-brain resolver `keep-majority`, `min-nr-of-members=1`, sharding, management +
  config-discovery) is `include`d — copied from Apollo and retuned for Artemis. *Alternative rejected:*
  a local single-writer registry — works single-node but doesn't scale and diverges from the siblings.
- **Projections + consume loop via ShardedDaemonProcess / a scheduled stream.** Projections run as
  distributed slice-range workers (as Apollo does). The Hermes consumer runs `pollOnce` on a repeating
  `Source.tick` (or `streamMessages` when we upgrade); a poll interval is configured. Both are started
  from `Main` after readiness. *Note:* `pollOnce` + in-memory `ProcessedJobs` means an at-least-once
  redelivery after a restart re-applies — safe because the domain transitions are idempotent (already
  reviewed); a durable store is the deferred hardening.
- **Tag-graph cache**: an `AtomicReference[TagGraph]` refreshed on a timer via `TagGraphRepository.loadGraph`;
  the `PostEntity` supplier and `SearchService` read the current snapshot. Bounded staleness (the
  refresh interval) is accepted — the write path canonicalizes against a recent graph, search resolves
  aliases against it; both tolerate sub-minute lag. Load once at startup before binding HTTP.
- **HTTP composition, search-first**: `HealthRoutes ~ SearchRoutes.routes ~ CatalogRoutes.routes ~
  mediaRoutes` — search-first so `/posts/facets` isn't captured by `/posts/{id}` (pinned by the §5
  route test). One `Http().newServerAt(host, port).bind(...)`, wired into CoordinatedShutdown for a
  graceful drain (mirror `HttpServer`).
- **Docker via sbt-native-packager** (copy Apollo's block): `dockerBaseImage` a JRE, non-root UID 1001,
  `HEALTHCHECK` hitting `/health` over bash `/dev/tcp`, `EXPOSE` the HTTP (and management) ports,
  `dockerEnvVars += LOG_FORMAT -> json`, `dockerRepository` from `DOCKERHUB_USERNAME`, dynver tag.
  Add `BuildInfoPlugin` so `/health` reports the version.
- **Metrics**: a lightweight Prometheus text endpoint (JVM + process + a few app gauges/counters —
  e.g. projection offset lag, consume-loop applied count). Prefer a minimal hand-rolled text exposition
  (as HermesMQ's `PrometheusText` does) over pulling a heavy client, unless a client is already transitive.

## Risks / Trade-offs

- **[Provider `local` → `cluster` breaks existing local runs / tests that assume `local`]** → the
  route/persistence tests already inject their own config; the e2e and any run use the cluster config.
  Keep unit tests on the testkit journal / `ScalatestRouteTest` (unaffected). Verify the full suite
  after the switch.
- **[At-least-once redelivery re-applies after a restart (in-memory `ProcessedJobs`)]** → domain
  idempotence covers correctness; note the duplicate-work window; durable store deferred.
- **[Tag-graph staleness between refreshes]** → bounded by the interval; acceptable at personal scale.
- **[e2e needs Apollo + Hermes]** → mock them in-process (bind fake `ObjectApi`/`PubSubService` power
  APIs, as the unit ITs already do) so the e2e stays Docker-only-for-Postgres; Hephaestus is a fake
  `MediaProcessed` publish.
- **[Cluster-of-one still forms a cluster]** → `min-nr-of-members=1` + SBR keep-majority (Apollo's
  proven config); a single node is healthy.

## Migration Plan

Additive assembly; no capability behavior changes. Steps: (1) build.sbt — native-packager + Docker,
cluster/management deps, buildinfo; (2) `cluster.conf` + provider switch; (3) `PostSharding`/`PoolSharding`;
(4) the tag-graph cache + `PersistenceReadiness`; (5) `Main` wiring projections + consume loop + graph
cache + HTTP bind; (6) `/health` + `/metrics`; (7) the e2e IT. Rollback = the service simply isn't
started; the seams are unchanged and independently tested.

## Open Questions

- Poll interval for the Hermes consume loop, and the tag-graph refresh interval (config, sane defaults).
- Whether to add cluster-bootstrap/management now or keep config-discovery single-node until the k8s
  deploy needs it (lean: config-discovery now, like Apollo; DNS/k8s discovery is a config swap later).
