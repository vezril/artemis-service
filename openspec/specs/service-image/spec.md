# service-image Specification

## Purpose
TBD - created by archiving change assemble-runnable-service. Update Purpose after archive.
## Requirements
### Requirement: Runnable Docker image

The service SHALL be packaged as a runnable Docker image via `sbt-native-packager`. The image SHALL
run the assembled `Main` entrypoint, expose the HTTP port, and be versioned by `sbt-dynver` so the
tag matches the source revision, consistent with the sibling Codex services.

#### Scenario: Image starts the service

- **WHEN** the built image is run with valid configuration and reachable dependencies
- **THEN** the container boots the Artemis runtime and serves the HTTP surface on the exposed port

#### Scenario: Image is version-tagged from the revision

- **WHEN** the image is built
- **THEN** its tag is derived from the git revision via dynver, matching the versioning scheme of the
  other Codex services

### Requirement: Non-root container user

The image SHALL run as a non-root user. The service process inside the container SHALL NOT run as
uid 0.

#### Scenario: Process runs unprivileged

- **WHEN** the container is running
- **THEN** the service process is owned by a non-root user

### Requirement: JSON logging by default in the image

The image SHALL set `LOG_FORMAT=json` as an environment default so containerized runs emit
structured JSON logs without additional configuration, while a local (non-image) run continues to
default to human-readable text.

#### Scenario: Container logs are JSON

- **WHEN** the container starts without overriding `LOG_FORMAT`
- **THEN** log output is emitted in the structured JSON format

#### Scenario: Local run stays human-readable

- **WHEN** the service is run outside the image without setting `LOG_FORMAT`
- **THEN** log output defaults to the human-readable text format

### Requirement: Container healthcheck

The image SHALL define a `HEALTHCHECK` that probes the service's health endpoint so orchestrators can
observe container liveness/readiness.

#### Scenario: Healthcheck reflects service health

- **WHEN** the service is bound and healthy
- **THEN** the container healthcheck reports healthy; and when the service is not ready, the
  healthcheck reports unhealthy

