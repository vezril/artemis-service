# Artemis — Build Roadmap

The incremental build plan for Artemis (catalog + tags + search + API). It mirrors how
the sibling services were built — Apollo and Hermes each opened with one **foundation**
change and then landed features one at a time — and diverges only in having more feature
milestones, because Artemis is the largest service.

## How to read this

- **Each milestone is one OpenSpec change = one PR**, built **test-first** (Red → Green →
  Refactor), matching the house TDD workflow.
- The milestones **implement** the captured design:
  [`design-artemis-tag-search`](openspec/changes/design-artemis-tag-search/) (tag model +
  search DSL) and [`design-artemis-internals`](openspec/changes/design-artemis-internals/)
  (aggregates, projections, ingest, catalog API).
- Stack (from the design): Scala 3 + Apache Pekko, event-sourced CQRS, PostgreSQL journal +
  Pekko Projections, Docker → Docker Hub.

## Milestones

| # | Milestone | Delivers | Depends on | Demoable value |
|---|-----------|----------|-----------|----------------|
| **M0** | Foundation | git + GitHub repo, CI/CD (semver, `main`/`development`), runnable Pekko HTTP health service, Docker image → Docker Hub, README + MIT license, HOCON/env config | — | Runnable skeleton in Docker |
| **M1** | Core domain (pure) | `Post` + `Pool` aggregates — value types, command/event ADTs, `decide`/`evolve`, tag-canonicalization as pure functions. Zero Pekko. | M0 | Domain fully unit-tested |
| **M2** | Tag relationships | `tags`/`aliases`/`implications` tables, transitive-closure cache, the canonicalization pipeline (alias rewrite → implication expand → dedup) | M1 | Canonical tag sets |
| **M3** | Event persistence | `Post`/`Pool` as `EventSourcedBehavior` on the PostgreSQL journal; verified crash recovery | M1 | Durable, replayable write side |
| **M4** | Projections | Pekko Projections → `posts`/`tags`/`pools` read tables (GIN on tags, `pg_trgm` on names); `post_count`/`fav_count`; rebuild-from-journal | M2, M3 | Query-ready read model |
| **M5** | Search DSL | tokenize → parse → AST → query plan → SQL; wildcard expansion, flat-`~`-OR, search-time alias resolution, guardrail caps; autocomplete + facets queries | M4 | The DSL works over real data |
| **M6** ★ | Catalog API (read) | pekko-http: `GET /posts` (DSL, order, keyset), `/posts/{id}`, `/tags/autocomplete`, `/posts/facets`, pool reads, **+ the HTTP media gateway** (Apollo over HTTP, range) | M5 | **Muses can browse + search** |
| **M7** | Catalog API (write) | `POST`/`PATCH` as entity commands: create post, change tags, favorite, score, pools | M3, M6 | Editing works (optimistic backend) |
| **M8** ★ | Ingest + media | Apollo gRPC client (md5-on-stream upload), HermesMQ publish `ProcessMediaJob` + consume `MediaProcessed`/`MediaFailed`, post-processing phash dup flag | M7 | **Real upload → process → active spine** |
| **M9** | Cross-cutting | Prometheus metrics, deployment (Codex Helm chart), Muses end-to-end | M8 | Production-operable |

## Sequencing notes

- **Two ★ payoff milestones.** M6 is the first real "wow" — Muses can browse and search
  actual data (seed the read model to demo it even before ingest exists). M8 completes the
  async spine.
- **Nothing blocks on Hephaestus.** M8 integrates against **Apollo and HermesMQ, which
  already exist**; the media worker is **mocked** in tests (fake `MediaProcessed` messages),
  so Artemis can be built end-to-end before Hephaestus is written.
- **Layer-by-layer** (domain → persistence → projections → API), like Apollo/Hermes. A thin
  "walking skeleton" vertical slice first is the alternative; layered is lower-risk for a
  solo TDD build.
- **Users are deliberately absent.** Single-user → favorites/scores are global, no `User`
  aggregate in the core roadmap.
- **M0 is real work** — `artemis-service` is currently just OpenSpec specs on disk (not yet
  a git repo), so M0 bootstraps git/GitHub/scaffold/CI/Docker exactly as the siblings did.

## Post-v1

- **M10+ Multi-user** — a `User` aggregate, auth, per-user favorites/scores, permissions.
  Additive: the events already carry the shape, so identity slots in without re-architecting.
- Later: moderation queues, tag wiki, saved searches, notes/annotations, comments.
