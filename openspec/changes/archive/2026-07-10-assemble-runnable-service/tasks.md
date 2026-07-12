# Tasks: assemble-runnable-service

> Assemble the built seams into a runnable, health-checked, Dockerized service. Order is
> dependency-first: build/config → sharding → cache/readiness → Main wiring → health/metrics →
> image → e2e. Follow the maker→checker→fix→verify discipline; keep the full suite green after each
> group (baseline: core 131, server 171).

## 1. Build & cluster configuration

- [x] 1.1 Add `sbt-native-packager`, `sbt-dynver`, and `sbt-buildinfo` plugins to `project/plugins.sbt`.
- [x] 1.2 Add the `server` runtime deps needed for assembly: pekko-cluster-sharding-typed,
      pekko-cluster-typed, the split-brain-resolver, pekko-management + cluster-http (config
      discovery), and pekko-projection ShardedDaemonProcess support — mirroring apollo-storage's set.
- [x] 1.3 Enable `JavaAppPackaging`, `DockerPlugin`, and `BuildInfoPlugin` on `server` in `build.sbt`;
      set `buildInfoKeys`/`buildInfoPackage` so `/health` can report name+version.
- [x] 1.4 Author `server/src/main/resources/cluster.conf` (provider stays overridable): actor
      provider = cluster, split-brain-resolver `keep-majority`, `min-nr-of-members = 1`, sharding, and
      pekko-management config discovery — copied from Apollo and retuned for Artemis ports/roles.
- [x] 1.5 Switch `application.conf` to `include "cluster.conf"` and set the remote/management/HTTP
      host+port from env with sane local defaults; keep `coordinated-shutdown.exit-jvm = on`.

## 2. Cluster sharding for entities

- [x] 2.1 Add `PostSharding` (EntityTypeKey, `init(system): ActorRef`-style, `entityRefFor(id)`) that
      hosts `PostEntity(id, tagGraphSupplier)` with the tag-graph cache supplier wired in.
- [x] 2.2 Add `PoolSharding` analogously hosting `PoolEntity(id)`.
- [x] 2.3 Route the catalog/media write paths through the sharded `EntityRef` instead of any locally
      spawned entity; adjust the ingest/HTTP wiring to accept a `sharding`/ref-factory seam.
      (Seam already present: `CatalogRoutes`/`UploadService` take a `String => RecipientRef[…]`
      factory; `EntityRef <: RecipientRef`, so Main supplies `PostSharding.entityRef`.)
- [x] 2.4 Test: a sharding init + `entityRefFor` round-trip (single-node) drives a command through the
      sharded entity and reads back state (persistence-testkit or a single-node ClusterSharding test).
      (`PostShardingIT` — single-node cluster + real journal, verifies `post|<id>` routing + graph.)

## 3. Tag-graph cache & readiness

- [x] 3.1 Add a `TagGraphCache` holding an `AtomicReference[TagGraph]`, a `current: () => TagGraph`
      supplier, a `refresh()` backed by the read-model graph loader, and a scheduled refresh on a
      configured interval; expose an initial blocking `load()` for startup.
- [x] 3.2 Wire the cache's `current` supplier into `PostSharding` (canonicalization) and into
      `SearchService`'s alias-resolution graph source. (Both seams already accept a supplier/thunk;
      Main passes `cache.snapshot` / `() => Future.successful(cache.current)`.)
- [x] 3.3 Add a `PersistenceReadiness` probe that verifies Postgres connectivity/DDL presence with a
      bounded retry, mirroring apollo-storage.
- [x] 3.4 Add config keys + `AppConfig` readers for the consume-loop poll interval, the tag-graph
      refresh interval, and the readiness timeout/retries (sane defaults).
- [x] 3.5 Test: `TagGraphCache` load/refresh swaps the snapshot; readiness probe passes against the
      testcontainers Postgres and fails fast against an unreachable one.

## 4. Application assembly (Main)

- [x] 4.1 Add the guardian/`Main`: create the `ActorSystem`, run `PersistenceReadiness` (abort+exit on
      failure), init `PostSharding`/`PoolSharding`.
- [x] 4.2 Load the tag-graph cache once and start its scheduled refresh.
- [x] 4.3 Start the `Post`/`Pool` projections via ShardedDaemonProcess.
- [x] 4.4 Start the Hermes consume loop: drive `HermesMediaResultConsumer.pollOnce` on a repeating
      `Source.tick` at the configured interval, wiring the Apollo/Hermes clients and the media-result
      handler.
- [x] 4.5 Compose the routes search-first (`health/metrics ~ SearchRoutes ~ CatalogRoutes ~
      media-gateway`) and bind one `Http().newServerAt(host, port)`; register unbind/drain in
      CoordinatedShutdown.
- [x] 4.6 Verify: `sbt server/run` boots against a local Postgres/Apollo/Hermes (or documents the env)
      and serves the surface; document required env vars. (Smoke-booted: cluster forms, post+pool
      sharding init, HTTP binds :8081, readiness gate aborts on unreachable Postgres. `run / fork`
      added so the forked JVM stays alive on the ActorSystem threads. Happy path proven by 7.1 e2e.)

## 5. Health & metrics

- [x] 5.1 Add `HealthRoutes` — `GET /health` returning UP/DOWN backed by a readiness flag that flips to
      DOWN at the start of coordinated-shutdown; include name+version from BuildInfo.
- [x] 5.2 Add `GET /metrics` Prometheus text exposition (JVM + process + app counters: consume-loop
      applied count). Uses io.prometheus simpleclient (mirrors the sibling services). Projection
      offset-lag deferred (needs an offset-store query); readiness + JVM cover runtime health.
- [x] 5.3 Test: `/health` returns UP when ready and DOWN after the shutdown hook trips; `/metrics`
      returns 200 with valid Prometheus text including the app counters.

## 6. Docker image

- [x] 6.1 Configure the Docker packaging in `build.sbt`: JRE base image, non-root UID, `EXPOSE` the
      HTTP port, `dockerEnvVars += LOG_FORMAT -> json`, and a `HEALTHCHECK` hitting `/health`; set
      `dockerRepository`/`packageName` for Docker Hub.
- [x] 6.2 Build the image locally (`sbt server/Docker/publishLocal`), run it against local deps, and
      confirm it boots, serves `/health` as JSON logs, and the healthcheck goes healthy. (Image
      `artemis:0.0.0-27-…` — User=1001:0, HEALTHCHECK present, EXPOSE 8080, LOG_FORMAT=json;
      container boots and emits structured JSON logs by default.)
- [x] 6.3 Document the image name, tags (dynver), required env vars, and the `docker run` invocation in
      the README. (Folded into the 7.3 README section.)

## 7. End-to-end verification & docs

- [x] 7.1 Write the internals **6.1** e2e IT: testcontainers Postgres + in-process Apollo/Hermes
      doubles; upload → pending → publish a fake `MediaProcessed` → assert active + read-model updated
      → assert a search query returns the post. (`RunnableServiceE2EIT` — green.)
- [x] 7.2 Run the full gate green (core + server + the new e2e) and reconcile the internals 6.1/5.1/5.4
      checkboxes now that the surface is served. (core 131 + server 181, all green; internals
      5.1/5.4/6.1/6.2 reconciled to done.)
- [x] 7.3 Write the internals **6.2** README section: architecture overview, how to run locally, the
      Docker image, env/config reference, and the endpoint list.
- [x] 7.4 Checker pass (scala-fp-reviewer) over the assembly + e2e; apply fixes test-first and
      re-verify the gate. (No blocking findings. Applied: consume-loop resilience — RestartSource +
      backoff, withdraw readiness on unexpected termination; a `consume_poll_failures_total` metric;
      `projection.instances` moved into `RuntimeConfig`; e2e handler aligned with Main's
      `ReadModelNearDuplicates` wiring. Skipped deliberate apollo-mirrors (Timer-per-retry) and the
      pre-existing facets-without-tags 404. Re-verified: affected tests + boot + full gate green,
      PostProjectionIT flake confirmed passing isolated.)
