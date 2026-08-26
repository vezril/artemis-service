# service-docs Specification

## Purpose
TBD - created by archiving change design-artemis-api-docs. Update Purpose after archive.
## Requirements
### Requirement: The API serves its own documentation

Artemis MUST serve its OpenAPI 3 spec at `GET /openapi.yaml` and an interactive Swagger UI at
`GET /docs`. The docs page's spec reference MUST be relative, so both work directly and through
the artemis-ui BFF prefix. The spec MUST be updated (and its version bumped) in the same change as
any API-surface modification.

#### Scenario: Spec served
- **WHEN** `GET /openapi.yaml` is requested
- **THEN** the OpenAPI 3 document is returned, containing every public endpoint

#### Scenario: Web docs
- **WHEN** `GET /docs` is requested
- **THEN** an HTML page rendering the spec (Swagger UI) is returned, referencing the spec relatively

