# Change: design-artemis-api-docs

> Calvin's ask: web-accessible API documentation (Swagger), an Insomnia collection, and an
> LLM-handoff file kept current with every feature.

## Why

The API surface is complete and stable but undocumented outside the OpenSpec specs and code.
Three consumers need three artifacts: a human browsing docs (web Swagger UI), a human driving the
API by hand (Insomnia), and an LLM session picking up the repo cold (a maintained handoff file).

## What Changes

- A hand-authored **OpenAPI 3 spec** (`server/src/main/resources/openapi.yaml`) covering the full
  surface, served live: `GET /openapi.yaml` (the spec) and `GET /docs` (Swagger UI page; viewer
  loads from CDN, the spec itself is self-hosted). Reachable through the BFF (`/api/artemis/docs`)
  because the page's spec URL is relative.
- **`docs/insomnia-collection.json`** — an Insomnia v4 export of every endpoint, grouped, with an
  environment-driven `base_url` and id placeholders.
- **`CLAUDE.md`** at the repo root — the LLM handoff (architecture, API pointers, behavioral
  truths, working conventions, state & open threads) with an explicit maintenance contract:
  updated in the same PR as every change it describes.

## Capabilities

### New Capabilities
- `service-docs`: the served OpenAPI spec + Swagger UI page, kept in step with the API.

## Non-goals

- Generated-from-code OpenAPI (the hand-authored spec is the documented source of truth, updated
  with every endpoint change — enforced by the CLAUDE.md contract and a sentinel route test).
- Vendoring the Swagger UI bundle (CDN trade-off documented in DocsRoutes).
