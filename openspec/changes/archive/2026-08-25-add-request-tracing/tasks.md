# Tasks — add-request-tracing (Artemis)

## 1. Dependency
- [x] Pin the-lexicon version carrying `Message.correlation_id` / `PublishRequest.correlation_id` + the shared `CorrelationNames` constants.

## 2. Async propagation primitive
- [x] MDC-propagating `ExecutionContext` (symmetric snapshot/restore, no leak) — mirror Apollo's.

## 3. Boundary — mint-or-adopt
- [x] HTTP: route wrapper that mints a correlation id (ignoring client `X-Correlation-Id`), sets MDC, seals inner routes below the response mapping so the header echoes on 2xx/4xx/5xx/404, and access-logs INFO on receipt + completion (method, path, status, duration).
- [x] Hermes consumers (`MediaProcessed`, `TagSuggestions`): read `Message.correlation_id` off the delivery, adopt into MDC (mint if empty), run the handler on the propagating EC.

## 4. Outbound propagation
- [x] Apollo gRPC client: attach `x-correlation-id` metadata (current id) to every call.
- [x] Publish path (`ProcessMediaJob`): set `PublishRequest.correlation_id` to the current id.

## 5. Logging config (structured-logging MODIFIED)
- [x] Ensure `correlationId` is in the MDC on the hot paths (encoder already promotes MDC → JSON fields).
- [x] `logback.xml`: `<root level="${LOG_LEVEL:-INFO}">` + `<logger name="…artemis…" level="${LOG_LEVEL_ARTEMIS:-INFO}">` (verbosity without rebuild).

## 6. Verify
- [x] Ingest trace: `POST` ingest → assert the Apollo call metadata and the published `ProcessMediaJob.correlation_id` both carry the request's minted id, and the response echoes `X-Correlation-Id`.
- [x] Consume trace: deliver a `MediaProcessed` with a `correlation_id` → assert the activation log line carries that same id (adopted, propagated across the async boundary).
- [x] Edge: a client-supplied `X-Correlation-Id` on the HTTP API is ignored (a fresh id is minted) — anti-injection.
- [x] No payloads/tokens/secrets in logs, even at TRACE.
- [x] `openspec validate add-request-tracing --strict`.
