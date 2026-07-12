# Tasks: add-structured-logging

Format-only; no behavioral change. Mirrors the `new-scala-service` skill template.

- [x] 1. (impl) add `net.logstash.logback % logstash-logback-encoder % 8.0` to the `server` module
- [x] 2. (impl) `server/src/main/resources/logback.xml`: a `json` appender (`LogstashEncoder`, `<customFields>{"service":"artemis"}</customFields>`) and a `text` appender, selected by `<property name="LOG_APPENDER" value="${LOG_FORMAT:-text}"/>` → `<appender-ref ref="${LOG_APPENDER}"/>` (default `text` for local `sbt run`/tests)
- [x] 3. (test) the JSON encoder emits the constellation field schema for an error — `@timestamp`, `level`, `logger_name`, `thread_name`, `message`, `service` = `artemis`, `stack_trace`, MDC as top-level; single-line
- [x] 4. (deploy) set `LOG_FORMAT=json` as the Docker **image env default** — DONE in M9
      (assemble-runnable-service): `build.sbt` sets `dockerEnvVars += LOG_FORMAT -> "json"`, and the
      built image was verified to emit structured JSON logs by default (a local `sbt run`/tests stays
      `text`). The `logback.xml` honors `LOG_FORMAT=json` when set.
