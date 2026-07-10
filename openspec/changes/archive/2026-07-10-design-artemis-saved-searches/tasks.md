# Tasks: design-artemis-saved-searches

TDD. Small event-sourced aggregate + a run path that reuses the search DSL.

## 1. Aggregate

- [x] 1.1 (test) save / rename / remove / list; unique name; deterministic save-existing behavior
      (`SavedSearchesDomainSpec` — 13 cases, decide/evolve; upsert-on-save chosen for save-existing.)
- [x] 1.2 (impl) `SavedSearches` aggregate (events) + projection for the list
      (Aggregate: `SavedSearchesDomain` + `SavedSearchesEntity` (single fixed instance) + sharding +
      Jackson serialization, proven by `SavedSearchesPersistenceIT`. NO separate projection/read
      table: for a single-instance list, the route reads the entity directly — read-your-writes, no
      lag, no table. Noted deviation from "+ projection"; a read table would only be needed by the
      deferred watermark/feed feature.)

## 2. Run + API

- [x] 2.1 (test) running a saved search = executing its stored DSL query (same results as typing it)
      (`SavedSearchRoutesSpec` — asserts the stored query reaches the search fn verbatim.)
- [x] 2.2 (impl) endpoints: `GET /saved-searches`, `POST/PATCH/DELETE`, run = resolve → DSL search
      (`SavedSearchRoutes` — `GET /saved-searches/{name}/results` resolves the query then runs the
      normal DSL search. Shared `HttpErrors` centralizes the domain-error→status mapping.)

## Future (documented, not built)

- [ ] watermark per saved search → "new since last seen" via `id:>watermark` → new-match counts + feed
      (Intentionally deferred — out of scope for this change, per proposal/design.)
