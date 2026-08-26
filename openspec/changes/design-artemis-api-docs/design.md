# Design: design-artemis-api-docs

- **Hand-authored spec over codegen**: pekko-http has no first-class OpenAPI derivation; the
  surface is stable and versioned by release trains, so a maintained YAML (bumped alongside
  `info.version`) is simpler and more accurate than annotation scaffolding. Drift protection: the
  DocsRoutes spec test asserts sentinel paths per surface, and CLAUDE.md's maintenance contract
  makes spec updates part of every endpoint PR.
- **Serving**: `DocsRoutes` reads the spec once from classpath resources (fail-fast if missing);
  `/docs` is a minimal Swagger UI host page with a RELATIVE spec url so it works both direct and
  behind the artemis-ui BFF prefix. The viewer's JS/CSS load from unpkg (documented trade-off:
  spec fully self-hosted, interactive viewer needs the browser to reach the CDN once).
- **Insomnia v4 export**: one workspace, per-surface folders, `base_url` in the environment (a
  Homelab sub-environment points at the BFF), `post_id`/`pool_id`/`md5` as environment
  placeholders.
- **CLAUDE.md**: the Claude Code convention file (auto-loaded), doubling as the generic LLM
  handoff. Carries its own update contract; state sections are dated.
