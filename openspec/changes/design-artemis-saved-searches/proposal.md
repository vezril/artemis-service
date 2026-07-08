# Change: design-artemis-saved-searches

> **Design capture (explore mode).** Named, re-runnable saved searches (backend), with the
> subscription/feed extension documented for later. Builds on `design-artemis-tag-search`
> (the DSL) and `design-artemis-internals`. No code implemented.

## Why

Saving a DSL query under a name for one-click re-run is a small feature with a big growth
path: a saved search **+ a watermark** becomes a subscription/feed ("new matches since last
seen") — nearly free, because the DSL + keyset pagination already exist.

## Decisions carried in from exploration

| Decision | Choice |
|----------|--------|
| Now | **Saved searches** — name/save/rename/remove/list, run the stored query |
| Storage | a small **event-sourced** `SavedSearches` aggregate (consistent with the everything-ES call); single-user = one list, multi-user later = per-user (additive) |
| Later | **subscriptions/feeds** — add a watermark + new-match counts + a feed; documented, not built |

## What Changes

- **saved-searches** (new): store and manage named DSL queries and run a saved search (which
  resolves to its query and executes the normal search).

## Impact

- Affected specs: `saved-searches` is **ADDED**.
- Uses the search DSL (`design-artemis-tag-search`) to run the stored query.
- Serves: `design-muses-saved-searches` (the save button + saved list UI).
- Growth path (documented in design.md): a **watermark** per saved search → "new since last
  seen" via `id:>watermark` (keyset) → new-match badges + a "what's new" feed → the seed of
  subscriptions and, later, notifications.
- Out of scope: subscriptions/feeds/watermarks (future), per-user lists (multi-user era).
