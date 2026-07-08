# Design: related tags

Co-occurrence "related tags" as an incremental cosine projection. Captured in explore mode;
no implementation.

## The math (cheap to keep live)

```
   maintain:  co(X,Y) = # posts having both X and Y      (pairwise, sparse)
              n(X)    = # posts having X                  (per tag)
   on query:  cosine(X,Y) = co(X,Y) / sqrt(n(X) · n(Y))   → rank Y by cosine, take top-N
```

Cosine treats each tag as a vector over posts; `cat_ears`↔`cat_girl` align, `cat_ears`↔`1girl`
don't especially. That's why it surfaces *genuinely* related tags instead of the globally
common ones — the thing raw co-occurrence counts get wrong.

## Incremental, event-driven maintenance

A projection consumes Post events and updates only what changed — no rebuild:

```
   PostCreated / TagsChanged {old}→{new}:
        n(t) += / -= for tags added/removed
        co(a,b) += / -= for each affected pair (diff of old vs new tag set)
   PostPurged:
        remove the post's contribution (decrement its pairs + counts)
```

Stored as a sparse `tag_cooccurrence(a, b, count)` table + the `tags.post_count` you already
maintain. At a few-thousand-tag personal library, updates are a handful of rows per event and
the cosine query is a cheap lookup.

## Where it's surfaced (Muses)

```
   review queue / tag editor   related-tag QUICK-ADDS for the post's tags — stacks with Argus:
                               Argus says "what's in the image," related-tags says "what usually
                               goes with these tags." Together they catch what either misses.
   search sidebar              "related tags" to refine/explore (complements facets)
   per-tag page                a tag's related tags
```

The three suggestion sources, complementary:
```
   Argus         image  → tags     (visual)
   related-tags  a tag  → co-tags  (co-occurrence)   ← this
   facets        results→ tags     (navigation)
```

## The future extraction path (Ariadne) — documented, not built

If related-tags ever grows into real analytics, or a public event stream becomes useful for
several consumers, extract it:

```
   Artemis  ── NEW: publish catalog.events (PostCreated · TagsChanged · PostPurged) → HermesMQ
                     │
        Ariadne (dedicated analytics service) consumes → maintains co(X,Y)+n(X) externally →
                serves cosine related-tags (and future: recommendations, tag stats, trends)
```

Event-sourcing makes this a clean migration — the same co-occurrence is rebuildable from the
event history, just fed by an integration-event stream instead of the internal journal. The
incremental design here is identical; only *where it runs* changes. Deferred deliberately —
the load doesn't justify a service yet.

## Out of scope

The Ariadne service + `catalog.events` (future), precomputed/materialized related-tags
(on-demand cosine from the maintained counts is enough at this scale), Redis caching
(premature).
