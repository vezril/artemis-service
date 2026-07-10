# Change: add-structured-logging

> Emit application logs as structured JSON so they're machine-readable — queryable in Loki by field,
> reliably parseable for error tracking + the self-healing feedback loop, and correlatable across
> services by ids. Log *format* only; no behavioral change.

## Why

Free-text logs can't be reliably analyzed: extracting an error's level / exception / stack, or
filtering by a correlation id, means fragile regex over multi-line output. The constellation
observability stack (Loki) and the `self-healing-loop` design note (rung **L0**) both depend on
structured logs. This is the cheap foundation that unblocks them — one dependency + a logback change.

## What Changes

- **structured-logging** (new): `artemis` emits one JSON object per log event when containerized
  (`LOG_FORMAT=json`), and human-readable text in local dev. Fields follow the constellation-wide
  schema so logs are uniformly queryable across services.

Implementation (JVM / logback — mirrors the `new-scala-service` skill template):

- add dependency `net.logstash.logback % logstash-logback-encoder % 8.0`
- `logback.xml`: a `json` appender (`LogstashEncoder`, `<customFields>{"service":"artemis"}</customFields>`)
  and a `text` appender, selected by an env toggle —
  `<property name="LOG_APPENDER" value="${LOG_FORMAT:-text}"/>` then `<appender-ref ref="${LOG_APPENDER}"/>`
- set `LOG_FORMAT=json` as a Docker image env default (shipped image logs JSON; local `sbt run` stays text)

## Impact

- One runtime dependency + a `resources/logback.xml` change + one Docker env default. No API or
  behavioral change; existing tests are unaffected (they don't assert log format).
- Consumed by: the Codex observability stack (Loki) and the `self-healing-loop` error path.
- Out of scope: shipping logs anywhere (the collector's job) and any error-tracker SDK.
