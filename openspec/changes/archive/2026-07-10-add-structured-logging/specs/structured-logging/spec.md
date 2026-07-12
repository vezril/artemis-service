# structured-logging

## ADDED Requirements

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

#### Scenario: Fields are consistent across services
- **GIVEN** two services' JSON logs
- **WHEN** they are queried in Loki by `service` and `level`
- **THEN** the same field names resolve in both — no per-service schema drift
