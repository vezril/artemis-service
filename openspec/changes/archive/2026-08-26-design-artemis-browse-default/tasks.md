# Tasks: design-artemis-browse-default

- [x] 1.1 `SearchService.planned`: blank `rawTags` → `Right(empty QueryPlan)` with the order
  override applied, before parsing. Covers both `search` and `facets`.
- [x] 1.2 `SearchRoutes`: `tags` optional (default `""`) on `/posts` and `/posts/facets`.
- [x] 1.3 Tests: `GET /posts` with no `tags` → 200 page (route spec); pure-negation `-x` still
  400; SqlCompiler empty-plan SQL pinned (WHERE status default + ORDER BY id DESC); facets with
  no `tags` → 200 whole-catalog facets; `order` override on browse-all.
- [x] 1.4 artemis-ui: gallery empty message differentiates blank-query ("No posts yet.") from
  no-match; verify fixture parity (already matches-all) and live behavior in the browser.
- [x] 1.5 Full sbt gate + `openspec validate --strict`.
