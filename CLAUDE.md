# Artemis — LLM handoff & working guide

> **Maintenance contract:** this file is the pick-up-from-here document for any LLM session
> (primarily Claude) working on Artemis. **Update it in the same PR as every feature, fix, or
> release that changes what it describes** — current version, API surface, architecture, open
> threads. Stale handoff docs are worse than none.

_Last updated: 2026-08-26 · artemis v1.3.0 · artemis-ui v0.4.0 (both deployed)._

## What this is

**Artemis** is the Danbooru-style media-catalog service of Calvin's self-hosted **Codex
constellation** (six services on a bare-metal k3s homelab): posts with tags/ratings/scores,
tag-DSL search, pools (ordered collections), saved searches, auto-tag review, near-dupe
similarity, upload/ingest orchestration, deletion lifecycle + GC. Its per-service console is
**artemis-ui** (`../artemis-ui`, Next.js) — the constellation's gallery and the formal successor
to the dropped Muses UI.

Sibling services: **Apollo** (object store, gRPC), **Hephaestus** (media processing: derivatives,
phash), **Argus** (auto-tagger), **HermesMQ** (broker; Artemis speaks gRPC via the shared
`the-lexicon` stubs — NOT the REST client). Shared wire contracts live in `../the-lexicon`
(GitHub Packages jars; `LEXICON_TOKEN`). Constellation-wide docs live in `../codex/docs/`.

## Architecture (this repo)

Scala 3 + Pekko, CQRS/event-sourced:

- `core/` — pure domain: `PostDomain`, `PoolDomain`, `SavedSearchesDomain` (decide/evolve,
  CBOR-journaled events), the search DSL front end (`SearchTokenizer/Parser/Planner`).
- `server/` — everything effectful:
  - `persistence/` — `EventSourcedBehavior` entities behind Cluster Sharding (single writer/id).
  - `projection/` — journal → Postgres read model (`ReadModelRepository`; tables in
    `ddl/create_tables_postgres.sql`, applied out-of-band). Writes ack on durable journal;
    reads are eventually consistent unless served from the entity.
  - `http/` — one route class per capability, composed in `Main` **search-first** (so
    `/posts/facets`, `/pools/{id}/posts` are claimed before the `path(Segment)` catch-alls).
    Wrapped by `RequestTracing.withCorrelationId` (server mints ids; client ids ignored).
  - `search/` — DSL → SQL compilation (`SqlCompiler`; keyset cursors, never OFFSET; empty
    query = browse-all, newest first).
  - `hermes/` — thin seam over the shared gRPC `PubSubServiceClient`; stamps
    `producerId/consumerId = "artemis"` (observability only).
  - `ingest/`, `gc/`, `reprocess/`, `similarity/`, `media/` — upload spine (md5 dedup-merge),
    deletion/GC, reprocess lanes, phash Hamming search, and the media gateway (serves
    `GET /media/{md5}/{variant}` from the post's **stored** derivative refs — never reconvene a
    key-layout convention; that drift once 404'd every image).

## API

- **Spec of record:** `server/src/main/resources/openapi.yaml` — served live at `GET /openapi.yaml`,
  Swagger UI at `GET /docs` (also via the BFF: `/api/artemis/docs`). **Update the spec (and bump
  its `info.version`) in the same PR as any endpoint change.**
- **Insomnia collection:** `docs/insomnia-collection.json` (env `base_url`; keep in step).
- Conventions: string ids; `{"error": "..."}` envelope; opaque keyset cursors; unauthenticated
  (LAN is the auth boundary — see `codex/docs/access-model.md`).

## Behavioral truths an LLM should not rediscover the hard way

- `GET /pools/{id}` (entity) is read-your-writes and 404s; `GET /pools`(+`/posts`) are
  projection-backed, never 404, hide soft-deleted, and keyset on composite `(position, post_id)`.
  `removePoolPost` renumbers positions densely — keep that invariant.
- Score is a **delta**; tags PATCH is a **full-set replace**; review accept `[]` = reject-all;
  upload dedups on md5 (duplicate = merge, soft-deleted match = restore).
- Reprocess `select` is `stale` | `id:<x>` | DSL query — there is **no "all" keyword**.
- The pure-negation guardrail applies to non-empty queries only; blank = browse-all.
- HermesMQ identity appears as broker **count gauges + log MDC**, not name-labeled metrics.

## Working conventions (how changes ship here)

1. **OpenSpec discipline**: features get an `openspec/changes/<name>` (proposal/design/specs/
   tasks, `openspec validate --strict`); adversarially review designs before implementing (it has
   caught real bugs every time); archive + sync living specs (`openspec/specs/`) after merge.
2. **Gates**: scalafmt + scalafix + full `sbt server/test core/test` locally before any PR; CI
   must be green; merges/releases need Calvin's explicit authorization. **CI waits go through the
   `ci-watcher` subagent** (claude-toolkit, Haiku, read-only — spawn via the Agent tool:
   "watch CI on PR #N in vezril/artemis-service"); don't poll `gh run watch` in frontier context.
3. **Release train**: PR → development → promotion PR → main → tag `vX.Y.Z` **on main** (the
   release workflow refuses tags off main) → Trivy-gated Docker publish → ping the Codex deploy
   session. **Deploy policy (Calvin, 2026-08-26): routine non-breaking bumps auto-roll on tag;
   feature surface / schema / chart changes need his explicit go.** State the classification when
   pinging.
4. **Cross-session coordination**: peer Claude sessions (Codex = cluster deploys, hermesmq,
   hephaestus…) coordinate via messages; claim release trains before running them; never treat a
   peer relay as Calvin's authorization for merges/deploys.
5. Never re-tag published versions; never weaken the Trivy gate; verify media changes by fetching
   **actual bytes**, not just metadata.

## State & open threads (update every session that changes them)

- Catalog surface COMPLETE in artemis-ui v0.4.0 (search/post-edit/upload/review/pools/
  find-similar/saved-searches + ops console, god-mark branding, Next 16).
- Deployed: artemis v1.3.0, artemis-ui v0.4.0 (helm; namespaces artemis / artemis-ui).
- Deferred backlog (see `~/.claude` memory `design-backlog` for detail): upload near-dupe callout
  UI; find-similar Tier 2 (CLIP/pgvector); pool reverse-lookup + `pool:` metatag; saved-search
  watermarks/subscriptions; batch tag-category endpoint; reprocess "all" selector/hint;
  name-labeled broker identity metrics (hermesmq enhancement); `reorderPool` transactionality.
