# Tasks: design-artemis-saved-searches

TDD. Small event-sourced aggregate + a run path that reuses the search DSL.

## 1. Aggregate

- [ ] 1.1 (test) save / rename / remove / list; unique name; deterministic save-existing behavior
- [ ] 1.2 (impl) `SavedSearches` aggregate (events) + projection for the list

## 2. Run + API

- [ ] 2.1 (test) running a saved search = executing its stored DSL query (same results as typing it)
- [ ] 2.2 (impl) endpoints: `GET /saved-searches`, `POST/PATCH/DELETE`, run = resolve → DSL search

## Future (documented, not built)

- [ ] watermark per saved search → "new since last seen" via `id:>watermark` → new-match counts + feed
