# Tasks: add-structured-logging

Format-only; no behavioral change. Mirrors the `new-scala-service` skill template.

- [x] 1. (impl) add `net.logstash.logback % logstash-logback-encoder % 8.0` to the `server` module
- [x] 2. (impl) `server/src/main/resources/logback.xml`: a `json` appender (`LogstashEncoder`, `<customFields>{"service":"artemis"}</customFields>`) and a `text` appender, selected by `<property name="LOG_APPENDER" value="${LOG_FORMAT:-text}"/>` → `<appender-ref ref="${LOG_APPENDER}"/>` (default `text` for local `sbt run`/tests)
- [x] 3. (test) the JSON encoder emits the constellation field schema for an error — `@timestamp`, `level`, `logger_name`, `thread_name`, `message`, `service` = `artemis`, `stack_trace`, MDC as top-level; single-line
- [ ] 4. (deploy) set `LOG_FORMAT=json` as the Docker **image env default** — DEFERRED: Artemis has no deployable image yet, so this lands with the Docker/native-packager packaging (M0). The `logback.xml` already honors `LOG_FORMAT=json` when set.
