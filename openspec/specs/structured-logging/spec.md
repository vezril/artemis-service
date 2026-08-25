# structured-logging Specification

## Purpose
TBD - created by archiving change add-structured-logging. Update Purpose after archive.
## Requirements
### Requirement: JSON-structured logs in the container
The service SHALL emit each log event as a single-line JSON object when `LOG_FORMAT=json` (the default
in its container image), carrying at least a timestamp, level, logger, thread, message, the `service`
name, and — for errors — the exception's stack trace; MDC context SHALL appear as fields. In local
development (no `LOG_FORMAT` override) it SHALL emit human-readable text instead.

#### Scenario: An error is emitted as parseable JSON
- **GIVEN** `LOG_FORMAT=json`
- **WHEN** the service logs an ERROR with an exception
- **THEN** the output is one JSON object with `level` = `ERROR`, the `service` name, the logger, the
  message, and a `stack_trace` field — extractable without regex over multi-line text

#### Scenario: Local development stays human-readable
- **GIVEN** no `LOG_FORMAT` is set
- **WHEN** the service logs
- **THEN** the console output is human-readable text

### Requirement: Constellation-wide log field schema

The JSON logs SHALL use the shared field shape so queries resolve identically across services:
`@timestamp`, `level`, `logger_name`, `thread_name`, `message`, `service`, `stack_trace` (on error),
and any MDC entries as top-level fields. On the JVM this SHALL be realized with
`logstash-logback-encoder`, and the `service` field SHALL be `artemis`.

The schema SHALL include **`correlationId`** (the shared Lexicon name) as a top-level field carried
via the MDC: any log line emitted while a correlation id is in context — an HTTP request, a consumed
Hermes message, or the async work either spawns — SHALL bear it, so one `correlationId` query resolves
a flow across every service. No encoder change is required (MDC entries are already promoted to
fields); the requirement is that the id be in the MDC on the hot paths.

#### Scenario: Fields are consistent across services
- **GIVEN** two services' JSON logs
- **WHEN** they are queried in Loki by `service` and `level`
- **THEN** the same field names resolve in both — no per-service schema drift

#### Scenario: A correlated log line carries correlationId as a field
- **GIVEN** a request or consumed message handled under `correlationId=req-42`
- **WHEN** Artemis logs during it
- **THEN** the JSON line has a top-level `correlationId` field = `req-42`, queryable in Loki identically to the same field in other services' logs

