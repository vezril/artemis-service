# Design: saved searches

Named, re-runnable queries — and the subscription/feed growth path. Captured in explore mode;
no implementation.

## Now: saved searches

```
   a saved search = { name, query }        e.g. "cat videos" → cat_ears is:video
   SavedSearches aggregate (small, event-sourced): SearchSaved · SearchRenamed · SearchRemoved
   single-user → one list · multi-user later → keyed by user (additive)
   run a saved search = resolve its query → the normal DSL search (nothing new to build)
```

## Later (documented): subscriptions / feeds

A saved search **+ a watermark** = a subscription:

```
   saved: cat_ears is:video   + watermark: last-seen post id
   "new since last seen" = run the query anchored at  id:>watermark   (KEYSET — cheap)
   → "12 new in 'cat videos'" badges · a "what's new" dashboard across all saved searches
   → the seed of feeds/subscriptions and, eventually, notifications
```

This is nearly free precisely because the **DSL + keyset pagination** already exist — "new
matches" is just the saved query with an id lower-bound. Deferred until wanted.

## Out of scope

Watermarks/subscriptions/feeds (future), per-user lists (multi-user era).
