# Change: design-artemis-related-tags

> **Design capture (explore mode).** Co-occurrence "related tags" ("posts with X often have
> Y") as an **incremental cosine projection inside Artemis**, with a documented path to
> extract it into a dedicated **Ariadne** analytics service later. Builds on
> `design-artemis-internals` / `design-artemis-tag-search`. No code implemented.

## Why

Related tags accelerate tagging and aid discovery — the third tag-suggestion source
alongside Argus (image→tags) and facets (results→tags). It's a read-side analytic derived
from the tag data, and — as decided — a **projection** is exactly the right mechanism to add
it without touching Artemis's write path or bloating its core.

## Decisions carried in from exploration

| Decision | Choice |
|----------|--------|
| Metric | **Cosine similarity** of tag post-membership vectors (Danbooru's approach) — surfaces genuinely-associated tags, not ubiquitous ones (raw counts would just return `1girl` for everything) |
| Update | **Incremental, event-driven** — maintain `co(X,Y)` (pairwise co-occurrence) + `n(X)` (per-tag count) from Post events; adjust only affected pairs on a tag change; decrement on purge. No full recompute |
| Where | **In-Artemis projection** now — feeds off Artemis's own event journal; negligible load at personal scale |
| Later | **Extract to a dedicated analytics service (Ariadne)** if it ever grows / an integration-event stream is wanted — event-sourcing makes the extraction clean (documented, not built) |

## What Changes

- **related-tags** (new): a projection maintaining `co(X,Y)` + `n(X)` incrementally from
  `PostCreated` / `TagsChanged` / `PostPurged`, and a query returning the top cosine-related
  tags for a given tag, served from that projection.

## Impact

- Affected specs: `related-tags` is **ADDED** (one in-Artemis projection + a query).
- Serves: the review-queue / tag editor (quick-add related tags — stacks with Argus), search
  refinement, and per-tag pages (Muses surfaces these).
- Distinct from existing mechanisms: **facets** (tags in the current result set), **implications**
  (hard always-add rules), **aliases** (same tag renamed).
- Future extraction (documented): Artemis publishes `catalog.events` → a dedicated **Ariadne**
  service consumes them and maintains the co-occurrence externally — the first of a possible
  family of integration-event consumers. Low-regret: the co-occurrence is rebuildable from the
  event history in either location.
- Out of scope: the Ariadne service + `catalog.events` stream (future), precomputed
  materialization (on-demand cosine from the maintained counts is fine at this scale).
