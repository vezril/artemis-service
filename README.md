# Artemis

The event-sourced media **catalog** at the heart of a Danbooru-style, self-hosted media
service — the third member of the [Apollo](https://github.com/vezril/apollo-storage) /
[HermesMQ](https://github.com/vezril/hermesmq) event-sourced family. Artemis owns posts,
tags, pools, the tag-search read model, and the API that serves the UI.

> **Status:** runnable. The domain, event persistence, read-model projections, the async
> media spine (Apollo upload + HermesMQ consume), the tag-search DSL, the media gateway, and
> the HTTP API are implemented and tested, and assembled into a single clustered, Dockerized
> service (`me.cference.artemis.Main`). See the [roadmap](ROADMAP.md) and
> [`openspec/`](openspec/) for the full plan and specs.

## Architecture

Event-sourced CQRS, in the house style of the sibling services (Pekko-persistence journal +
Pekko Projections into PostgreSQL read tables):

```
  WRITE (entities)          JOURNAL             READ (projections)         API
  ┌──────────────┐  events  ┌──────────┐  tail  ┌────────────────────┐  ┌───────────┐
  │ Post entity  │────────▶ │ Postgres │──────▶ │ posts (tags[] GIN) │─▶│ pekko-http│
  │ Pool entity  │          │ journal  │        │ tags (pg_trgm)     │  │ REST/JSON │
  └──────────────┘          └──────────┘        │ pools · pool_posts │  └───────────┘
     commands                                    (rebuildable)
```

- **Write side** — `Post` and `Pool` are event-sourced aggregates: pure `decide`/`evolve`
  over immutable ADTs (in `core`, zero Pekko), wrapped as `EventSourcedBehavior`s on the
  R2DBC Postgres journal (in `server`). Tags are canonicalized (alias → implication → dedup)
  on the write path, so the journal is always canonical, and the `TagsChanged` history is the
  post's edit history for free.
- **Read side** — Pekko Projections tail the journals into query-optimized Postgres tables
  (`posts.tags text[]` + GIN, `tags` + `pg_trgm`, `pools`/`pool_posts`). The read model is
  **eventually consistent** and **rebuildable** — drop a table and replay the journal.
- **API** — pekko-http REST/JSON: writes translate to entity commands; `GET /posts/{id}`
  reads the entity for read-your-writes freshness; the tag-search DSL (`GET /posts`) reads
  the projections; the media gateway (`GET /media/...`) streams Apollo derivatives.
- **Runtime** — `Main` forms a Pekko cluster (of one by default), hosts the `Post`/`Pool`
  aggregates via **Cluster Sharding** (single writer per id), runs the projections
  (ShardedDaemonProcess) and the HermesMQ media-result **consume loop**, keeps a refreshed
  in-memory **tag-graph cache** for canonicalization/alias-resolution, and binds the composed
  HTTP surface once a Postgres **readiness probe** passes. Two external gRPC dependencies:
  **Apollo** (object store) and **HermesMQ** (the async media-job transport); Hephaestus does
  the actual media processing and returns results over Hermes.

## Modules

| Module   | Contents                                                            |
|----------|--------------------------------------------------------------------|
| `core`   | Pure domain — value types, command/event ADTs, `decide`/`evolve`, tag canonicalization. No Pekko. |
| `server` | Pekko runtime: `EventSourcedBehavior` entities, Jackson-CBOR serialization, projections + read repository, the catalog HTTP API. |

## Build & test

Requires JDK 21+ and sbt. Integration tests use [testcontainers](https://testcontainers.com/)
and need a running Docker daemon (they spin up `postgres:16-alpine`).

```bash
sbt "core/test"      # pure-domain unit tests (fast, no Docker)
sbt "server/test"    # persistence + projection + HTTP tests (needs Docker)
sbt test             # everything
sbt scalafmtCheckAll # formatting gate (CI)
```

The PostgreSQL journal + read-model schema lives in
[`ddl/create_tables_postgres.sql`](ddl/create_tables_postgres.sql); integration tests apply it
automatically, and deployment applies the equivalent DDL out of band.

## Running the service

`Main` needs a reachable PostgreSQL, Apollo, and HermesMQ. It applies **no** DDL itself — the
schema above must already exist. Startup is readiness-gated on Postgres: an unreachable
database aborts the boot (non-zero exit) rather than serving a half-wired process.

```bash
# Local run (forks a JVM that stays up on the ActorSystem threads):
POSTGRES_HOST=localhost APOLLO_HOST=localhost HERMES_HOST=localhost \
  sbt "server/run"
```

`GET /health` reports `UP`/`DOWN` (503 while draining), and `GET /metrics` serves Prometheus
text (JVM/process + consume-loop counters).

### Docker

The image is built with sbt-native-packager and tagged from the git revision via sbt-dynver
(matching the sibling services). It runs as a **non-root** user, `EXPOSE`s `8080`, defaults
`LOG_FORMAT=json` (structured logs for Loki), and defines a `HEALTHCHECK` against `/health`.

```bash
sbt "server/Docker/publishLocal"           # build artemis:<dynver> locally
# push to Docker Hub: set DOCKERHUB_USERNAME, then `sbt server/Docker/publish`

docker run --rm -p 8080:8080 \
  -e POSTGRES_HOST=… -e APOLLO_HOST=… -e HERMES_HOST=… \
  artemis:<tag>
```

## Configuration

Runtime config is HOCON with environment-variable overrides (no secrets committed) — see
`server/src/main/resources/` (`application.conf`, `persistence.conf`, `cluster.conf`).

| Variable | Default | Purpose |
|---|---|---|
| `HTTP_HOST` / `HTTP_PORT` | `0.0.0.0` / `8080` | HTTP API bind address |
| `POSTGRES_HOST` / `POSTGRES_PORT` | `localhost` / `5432` | journal + read-model DB |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `artemis` | DB credentials |
| `APOLLO_HOST` / `APOLLO_PORT` / `APOLLO_TLS` | `localhost` / `8443` / `false` | Apollo object store gRPC |
| `HERMES_HOST` / `HERMES_PORT` / `HERMES_TLS` | `localhost` / `8450` / `false` | HermesMQ pub/sub gRPC |
| `HERMES_TOPIC_MEDIA_PROCESS` | `media.process` | media-job publish topic |
| `HERMES_SUB_MEDIA_PROCESSED` / `HERMES_SUB_MEDIA_FAILED` | `artemis.media.*` | result subscriptions |
| `CONSUME_INTERVAL` / `TAG_GRAPH_REFRESH` | `2s` / `60s` | consume-loop poll / graph-cache refresh |
| `READINESS_RETRIES` / `READINESS_RETRY_DELAY` | `5` / `1s` | startup Postgres probe budget |
| `DEDUP_HAMMING_THRESHOLD` | `10` | max phash distance to flag a possible duplicate |
| `LOG_FORMAT` | `text` (image: `json`) | log encoder (`text` \| `json`) |
| `CLUSTER_HOST` / `CLUSTER_PORT` | `127.0.0.1` / `25520` | Pekko remoting |
| `MANAGEMENT_HOST` / `MANAGEMENT_PORT` | `127.0.0.1` / `8558` | Pekko management / bootstrap |
| `PROJECTION_INSTANCES` | `4` | distributed projection workers |
| `DISCOVERY_METHOD` / `CONTACT_POINT_HOST` | `config` / `127.0.0.1` | cluster formation (swap to `kubernetes-api`/`dns` in k8s) |

## HTTP API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/health` | liveness/readiness (`UP`/`DOWN`) |
| `GET` | `/metrics` | Prometheus text exposition |
| `GET` | `/posts?tags=&order=&cursor=&limit=` | tag-search DSL over the read model (keyset paged) |
| `GET` | `/posts/facets?tags=` | tag facets for a query, grouped by category |
| `GET` | `/tags/autocomplete?q=&context=tag\|metatag` | tag / metatag autocomplete |
| `POST` | `/posts` | create a pending post `{id, md5, filetype}` |
| `GET` | `/posts/{id}` | read a post (read-your-writes from the entity) |
| `PATCH` | `/posts/{id}/tags` · `/rating` | edit tags / rating |
| `POST`/`DELETE` | `/posts/{id}/favorite` · `POST /posts/{id}/score` | favorite / score |
| `POST` | `/pools`, `/pools/{id}/posts` · `PUT /pools/{id}/order` · `PATCH`/`DELETE /pools/{id}` | pool CRUD + membership |
| `GET` | `/pools/{id}` | read a pool (ordered id list, read-your-writes from the entity) |
| `GET` | `/pools?cursor=&limit=` | list pools (name-ordered, keyset paged) with visible count + cover |
| `GET` | `/pools/{id}/posts?cursor=&limit=` | a pool's members hydrated in order (keyset paged, same envelope as `/posts`) |
| `GET` | `/media/{md5}/{variant}` | stream an Apollo derivative (HTTP `206` range support) |

## License

[MIT](LICENSE) © 2026 Calvin Ference
