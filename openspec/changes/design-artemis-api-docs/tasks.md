# Tasks: design-artemis-api-docs

- [x] 1.1 Hand-author `server/src/main/resources/openapi.yaml` covering the full surface
  (health/metrics, search+browse-all, posts CRUD+edit, upload, reprocess, review, similarity,
  pools, saved searches, admin deletion/GC, media gateway, docs itself).
- [x] 1.2 `DocsRoutes` (`/openapi.yaml` + `/docs` with a relative spec url), wired in `Main`.
- [x] 1.3 `DocsRoutesSpec`: sentinel paths present in the served spec; docs page relative-url.
- [x] 1.4 `docs/insomnia-collection.json` (v4 export, env base_url + id placeholders, all
  endpoints grouped).
- [x] 1.5 `CLAUDE.md` at the repo root: LLM handoff with maintenance contract, architecture,
  behavioral truths, conventions, state & open threads.
- [x] 1.6 Full sbt gate + `openspec validate --strict`.
