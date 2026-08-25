# Change: add-request-tracing

> Adopt the constellation request-tracing standard in Artemis (Apollo is the JVM reference). Artemis
> is a full edge node: it serves an HTTP API, **consumes** Hermes results, **publishes** Hermes jobs,
> and **calls** Apollo over gRPC. So it mints-or-adopts a `correlationId` at each trust boundary, puts
> it on every log line, echoes it to callers, and — going beyond Apollo v1 — **propagates it
> downstream** onto its Apollo calls and the messages it publishes, so a flow stays stitched through it.

## Why

The standard: every service attaches a correlation id, carries it on every log line, and propagates
it, so one id stitches a logical operation across services (Apollo upload → Hermes → Artemis catalog →
…) and back. Artemis is where several hops meet — an ingest request calls Apollo, publishes a
`ProcessMediaJob`, and later consumes the `MediaProcessed`/`TagSuggestions` results asynchronously.
Without tracing, those four legs look like four unrelated events in Loki. With it, one `correlationId`
query shows the whole ingest→process→activate story.

Artemis exercises the two deltas the standard flagged beyond Apollo v1: **adopt-inbound at trusted
hops** (adopt the correlation id off a Hermes-delivered message) and **outbound propagation** (stamp
it onto Apollo gRPC calls + published Hermes messages). It's the fuller reference for the edge
services that follow.

Depends on **the-lexicon `add-request-tracing`**: the `correlation_id` envelope field (to read on
consume / set on publish) and the shared name constants. This change pins that version.

## What Changes

- **request-tracing** (new):
  - **Boundary — mint-or-adopt.** The HTTP API is untrusted ingress → **mint** a correlation id and
    ignore any client-supplied value (anti-injection, = Apollo v1). A Hermes-delivered message is a
    trusted internal hop → **adopt** its `correlation_id` (mint only if absent).
  - **Carry.** The id is on every log line (MDC), surviving `Future`/projection async boundaries via
    an MDC-propagating `ExecutionContext`. INFO access logs on receipt + completion. Verbosity is
    env-configurable (`LOG_LEVEL` root + `LOG_LEVEL_ARTEMIS` for Artemis's own code) without a rebuild.
  - **Return + propagate.** Echo `X-Correlation-Id` on HTTP responses (2xx/4xx/5xx). Propagate the id
    **downstream**: as `x-correlation-id` metadata on Apollo gRPC calls, and as `correlation_id` on
    every message published to Hermes (`ProcessMediaJob`).
  - **No sensitive data** in logs (no payloads/tokens/secrets), even at TRACE.
- **structured-logging** (MODIFIED): `correlationId` is a first-class MDC field in Artemis's JSON log
  schema (`service = artemis`).

Implementation (JVM/Scala+Pekko — mirrors Apollo):

- MDC-propagating `ExecutionContext` (symmetric snapshot/restore) for async correlation.
- HTTP route wrapper (sibling of the service-metrics wrapper) that seals inner routes below the
  response mapping so the header lands on success + error + 404; mints, access-logs, echoes.
- Consumer wrapper: read `Message.correlation_id` off the delivery → MDC for the handler (on the
  propagating EC) so processing a `MediaProcessed` correlates with the ingest that caused it.
- Outbound: attach `x-correlation-id` to the Apollo client `Metadata`; set `PublishRequest.correlation_id`
  when publishing to Hermes.
- `logback.xml`: `<root level="${LOG_LEVEL:-INFO}">` + a dedicated `<logger name="…artemis…"
  level="${LOG_LEVEL_ARTEMIS:-INFO}">`.

## Impact

- Affected specs: `request-tracing` **ADDED**; `structured-logging` **MODIFIED**.
- Depends on the-lexicon envelope field + constants (pin the release). No change to catalog/ingest
  behaviour — tracing rides the existing paths.
- Downstream: Apollo receives `x-correlation-id` on Artemis's calls; HermesMQ carries the
  `correlation_id` Artemis sets on published jobs → Hephaestus (consumer) can adopt it. This is the
  propagation that makes the ingest→process chain one trace.
- Out of scope: OTel spans/backend; changing the trust posture of the HTTP edge (mints, like Apollo —
  see `design.md` for when it could flip to adopt); minting on behalf of untrusted callers.
