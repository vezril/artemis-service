# request-tracing Specification

## Purpose
TBD - created by archiving change add-request-tracing. Update Purpose after archive.
## Requirements
### Requirement: Mint or adopt a correlation id at every trust boundary

Artemis SHALL establish a correlation id for every unit of work, per the trust of its origin. At the
**HTTP API** (untrusted ingress) it SHALL **mint** a fresh id and **ignore** any client-supplied
`X-Correlation-Id` (anti-injection). For a **Hermes-delivered message** (a trusted internal hop) it
SHALL **adopt** the delivery's `correlation_id`, minting only if that is empty. The established id
SHALL be available to all work handling that request/message via the runtime context (MDC).

#### Scenario: The HTTP API mints and ignores a client-supplied id
- **GIVEN** a request to `GET /posts` carrying `X-Correlation-Id: forged-123`
- **WHEN** Artemis handles it
- **THEN** it logs under a freshly minted id (not `forged-123`), and the client value is ignored

#### Scenario: A Hermes-delivered message's id is adopted
- **GIVEN** a `MediaProcessed` delivered with `correlation_id = req-42`
- **WHEN** Artemis consumes it
- **THEN** the handling of that result logs under `correlationId=req-42` (adopted from the delivery)

#### Scenario: Edge case — a delivery with no correlation id gets one minted
- **GIVEN** a delivered message whose `correlation_id` is empty
- **WHEN** Artemis consumes it
- **THEN** Artemis mints an id so the work is still traceable

### Requirement: Carry the correlation id on every log line and return it

The correlation id SHALL appear on every log line emitted while handling a request or message (via
the MDC), surviving `Future`/projection async boundaries. Artemis SHALL emit INFO access logs on
receipt and completion (method/subject, status, duration) bearing the id, and SHALL **echo**
`X-Correlation-Id` on HTTP responses (success, error, and not-found). Log verbosity SHALL be
configurable without a rebuild, with Artemis's own code independently raisable to TRACE. No log line
SHALL contain payloads, tokens, or secrets, even at TRACE.

#### Scenario: The response echoes the correlation id, including on errors
- **GIVEN** a request handled under a minted id
- **WHEN** Artemis responds — 200, a 4xx, or a 404
- **THEN** the response carries `X-Correlation-Id` with that id

#### Scenario: The id survives an async boundary
- **GIVEN** a handler that continues on a `Future`/projection off the request thread
- **WHEN** it logs after the async hop
- **THEN** the log line still carries the request's `correlationId` (propagated via the MDC-propagating execution context)

#### Scenario: Edge case — no sensitive data even at TRACE
- **GIVEN** `LOG_LEVEL_ARTEMIS=TRACE`
- **WHEN** Artemis logs on the hot path
- **THEN** the lines carry the correlation id and non-sensitive fields, and never a payload, token, or secret

### Requirement: Propagate the correlation id downstream

Artemis SHALL propagate its current correlation id onto the work it triggers: it SHALL attach
`x-correlation-id` metadata to outbound **Apollo gRPC** calls, and SHALL set `correlation_id` on every
message it **publishes to Hermes** (e.g. `ProcessMediaJob`). Both take the id from the current
request/message context, so a single flow stays stitched across the hop.

#### Scenario: An ingest propagates its id to Apollo and the published job
- **GIVEN** an ingest request handled under a minted id `req-7`
- **WHEN** Artemis stores the object in Apollo and publishes a `ProcessMediaJob`
- **THEN** the Apollo call carries `x-correlation-id: req-7` and the published message's `correlation_id` is `req-7`

#### Scenario: Edge case — the downstream consumer can continue the trace
- **GIVEN** a `ProcessMediaJob` published with `correlation_id = req-7`
- **WHEN** Hephaestus consumes it
- **THEN** the id is present to adopt (the bus carried it), so processing continues the same trace

