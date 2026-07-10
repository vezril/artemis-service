# Change: design-artemis-tag-search

> **Design capture (explore mode).** This change records the tag-model and
> search-DSL design for the **Artemis** service (catalog + tags + search + users
> + API). It originated in exploration against the Media Atlas umbrella and now
> lives in the Artemis repo where it belongs. No code is implemented by this change.

## Why

Artemis is the heart of the media service — a Danbooru-style catalog whose
defining feature is its **tag search DSL** (`1girl cat_ears rating:s score:>10
order:score -monochrome`). This DSL, and the tag model it queries (categories,
aliases, transitive implications), is the single most consequential piece of
domain design and was flagged as the missing mechanic. Capturing it now — grammar,
semantics, compilation to the read model, and the resolved design forks — prevents
the shape from evaporating and gives the future Artemis build a spec to work from.

The design assumes the broader architecture decided in exploration:

- Artemis follows an **Option-C hybrid**: the post lifecycle and tag edits are
  event-sourced (history/versioning for free, mirroring Apollo/HermesMQ), while
  search runs entirely against a **projection-built relational read model**
  (`posts.tags text[]` + GIN, `tags` + `pg_trgm`), never the entities.
- **Postgres-native search** (GIN array intersection + trigram autocomplete) —
  no dedicated search engine at homelab scale until measured otherwise.
- **Single-user now, multi-user later**: permission/moderation metatags are
  stubbed, not built.

## What Changes

- **tag-model** (new): tag name normalization (lowercase + underscores), the five
  tag categories, tag aliases (rewrite), transitive tag implications (expansion),
  the canonicalization pipeline that runs on the write path, and projection-maintained
  `post_count`.
- **search-dsl** (new): tokenization + grammar, positional tag AND, negation
  (tags and metatags) with a required positive anchor, **flat `~` OR groups**,
  wildcards with a bounded expansion, the metatag catalog (comparisons, ranges,
  enums, and video-specific tags), **search-time alias resolution**, ordering with
  keyset pagination and **seeded stable `order:random`**, guardrail caps, and
  compilation to the Postgres read model.

Resolved design forks (from exploration):

| Fork | Decision |
|------|----------|
| OR model | **Flat `~`** (Danbooru-style); parenthesized boolean is a possible later extension |
| Rating scale | **4-tier** `g` / `s` / `q` / `e` (modern Danbooru) |
| Tag names | **Force lowercase + underscores** |
| `order:random` | **Seeded / stable** (cursor carries the seed) |
| `status:` / moderation metatags | **Stubbed** now; wired when multi-user lands |

## Impact

- Affected specs: `tag-model` and `search-dsl` are **ADDED** (greenfield design
  capture for a service that does not yet exist).
- Affected code: none — this change implements nothing. It is a design record.
- Downstream: informs the Artemis repo scaffolding, the `posts`/`tags` read-model
  schema and indexes, and the Muses autocomplete contract
  (`GET /tags/autocomplete`). Depends on Apollo (blobs) and HermesMQ (ingest jobs)
  only indirectly — search is read-model-only.
