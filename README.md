# Artemis

The event-sourced media **catalog** at the heart of a Danbooru-style, self-hosted media
service — the third member of the [Apollo](https://github.com/vezril/apollo-storage) /
[HermesMQ](https://github.com/vezril/hermesmq) event-sourced family. Artemis owns posts,
tags, pools, the tag-search read model, and the API that serves the UI.

> **Status:** early build. The domain, event persistence, read-model projections, and the
> HTTP write API are implemented and tested; search, ingest, and the media gateway are next.
> See the [roadmap](ROADMAP.md) and [`openspec/`](openspec/) for the full plan and specs.

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
  reads the entity for read-your-writes freshness; list/search read the projections.

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

## Configuration

Runtime config is HOCON with environment-variable overrides (no secrets committed) — see
`server/src/main/resources/`. Postgres coordinates default for local/compose and are
overridable via `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` / `POSTGRES_USER` /
`POSTGRES_PASSWORD`.

## License

[MIT](LICENSE) © 2026 Calvin Ference
