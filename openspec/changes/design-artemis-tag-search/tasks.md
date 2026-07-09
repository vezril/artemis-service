# Tasks: design-artemis-tag-search

Implements the tag model (relationships/categories) and the search DSL over the projection read
model. TDD throughout (Red → Green → Refactor). Builds on the Artemis internals already merged:
tag-name **normalization** + the pure **canonicalization pipeline** (`TagCanonicalization`/`TagGraph`)
and the `tags(name, category, post_count)` projection already exist — this change adds the alias/
implication **tables + graph loading**, the **category** model, and the whole **DSL**.

Dependency order: tag relationships → DSL parse (pure) → resolve/plan → SQL/execute → API.

## 1. Tag relationships & categories (roadmap M2)

- [x] 1.1 (test) `TagCategory` enum (general 0, artist 1, copyright 3, character 4, meta 5); invalid value rejected
- [x] 1.2 (impl) DDL: `tag_aliases`, `tag_implications` (DIRECT edges; transitive closure stays in the reused pure `TagCanonicalization`); `tags.category` already present
- [x] 1.3 (test) `TagGraphRepository.loadGraph` loads a `TagGraph` from the tables (multi-consequent implications unioned); transitive canonicalize, alias chain→terminal, cycle-termination — testcontainers IT
- [x] 1.4 (test) write-path canonicalization uses the DB-loaded `TagGraph` — `PostEntity` given a defaulted `() => TagGraph` supplier read per command (never on replay); cache/refresh loop deferred to M9

## 2. Search DSL — tokenize & parse (pure `core`)

- [x] 2.1 (test) quote-aware tokenizer: splits on whitespace, keeps quoted phrases, recognizes `~ - : * ..` and the `> < >= <=` comparators
- [x] 2.2 (test) parser → AST: positional tags (AND), `-` negation (tags + metatags), flat `~` OR terms, `name:value` metatags, `*` wildcards, `a..b`/`a..`/`..b` ranges and `cmp scalar`
- [x] 2.3 (impl) the `SearchQuery` AST + tokenizer + parser (total, errors-as-values)
- [x] 2.4 (test) guardrails at parse: a positive anchor is required (only-negative rejected); max positive tags (~40) enforced
      <!-- Pure `me.cference.artemis.search`: tokenizer + `SearchParser.parse: Either[ParseError, SearchQuery]`.
           Malformed ranges (a..b..c), cmp-in-range-bound, and empty terms (~, trailing -) all rejected.
           The ~200 result-page ceiling is a limit/order concern (section 4), deferred. -->


## 3. Search DSL — resolve & plan

- [x] 3.1 (test) search-time alias resolution: query tag terms are rewritten through the `TagGraph` (aliases apply to the query, not only stored tags)
- [x] 3.2 (test) wildcard expansion: `cat_*` expands via the `tags` trigram index to concrete tags, folded into an OR, **capped** top-N by `post_count`; an over-broad wildcard is rejected ("refine your search")
- [x] 3.3 (test) query plan `{includes, excludes, orSet, predicates, order, limit, cursor}` derived from the resolved AST
- [x] 3.4 (impl) the resolver + planner (consumes `TagGraph` + a wildcard lookup port)

## 4. Search DSL — compile to SQL & execute (`server`)

- [ ] 4.1 (test) SQL shape: `tags @> :includes` (GIN), `NOT (tags && :excludes)`, `tags && :orSet` (only when non-empty), metatag predicates ANDed
- [ ] 4.2 (test) metatag catalog: scalars/cmp/range (`score`,`id`,`width`,`height`,`mpixels`,`ratio`,`filesize`,`favcount`); enums (`rating` g|s|q|e, `filetype`, `status` STUB); video (`duration`,`fps`,`filetype` webm|mp4|gif,`is:video`,`is:animated`,`audio`); `date`/`age`
- [ ] 4.3 (test) ordering + **keyset** pagination (`order:id|score|favcount|duration|mpixels|filesize`, composite `(key,id)` cursor, never OFFSET); `order:random:SEED` seeded + stable (seed carried in the cursor)
- [ ] 4.4 (impl) SQL compiler + `ReadModelRepository` execution, verified against Postgres (testcontainers)

## 5. Catalog API — search, facets, autocomplete (unblocks internals 5.1/5.4)

- [ ] 5.1 (test) `GET /posts?tags=<DSL>&order=&cursor=` returns a keyset page of matching post summaries from the read model
- [ ] 5.2 (test) `GET /posts/facets?tags=<DSL>` returns the tags across the matching posts, grouped by category with per-tag counts
- [ ] 5.3 (test) `GET /tags/autocomplete?q=&context=tag|metatag` — trigram-backed, `post_count`-ranked, grammar-aware (mid-tag ⇒ tags with category + `alias_of`; mid-metatag ⇒ enum values)
- [ ] 5.4 (impl) pekko-http routes wired to the DSL compiler + repository (extends `CatalogRoutes`)
