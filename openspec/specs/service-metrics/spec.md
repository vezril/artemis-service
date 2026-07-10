# service-metrics Specification

## Purpose
TBD - created by archiving change assemble-runnable-service. Update Purpose after archive.
## Requirements
### Requirement: Prometheus metrics endpoint

The service SHALL expose a Prometheus-format metrics endpoint (`GET /metrics`) suitable for scraping.
The exposition SHALL include JVM and process metrics and a small number of application metrics
covering the runtime's health (e.g. read-model projection progress and consume-loop applied count).

#### Scenario: Metrics are scrapable

- **WHEN** a scraper issues `GET /metrics`
- **THEN** the service responds 200 with a valid Prometheus text-format body containing JVM/process
  metrics and the application metrics

#### Scenario: Application counters reflect activity

- **WHEN** the consume loop applies a media result and a projection advances its offset
- **THEN** the corresponding application metrics reflect that activity on the next scrape

