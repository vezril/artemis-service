# static-analysis

## MODIFIED Requirements

### Requirement: Dependency and image vulnerability scanning
The project SHALL receive dependency-update / CVE alerts via Dependabot (sbt + github-actions
ecosystems), and the release pipeline SHALL scan the published Docker image for known vulnerabilities
with Trivy at `CRITICAL,HIGH` severity, ignoring unfixed findings.

The release image SHALL carry **no fixable CRITICAL/HIGH CVEs**: a Trivy scan at those severities
(with unfixed findings ignored) SHALL report zero findings, so the release gate passes rather than
merely surfacing the result. Fixable CVEs that originate in **transitive** dependencies SHALL be
remediated by pinning the patched versions on the classpath via `dependencyOverrides`, since bumping
only the directly-declared dependencies does not move a transitively-resolved artifact.

#### Scenario: The image is scanned on release
- **WHEN** the release workflow builds the service image
- **THEN** the image is scanned for known CVEs and the result is surfaced, failing on the configured severities

#### Scenario: The release image has no fixable high-severity CVEs
- **WHEN** the built image is scanned with Trivy at `CRITICAL,HIGH` severity with unfixed findings ignored
- **THEN** the scan reports zero findings and the release gate passes

#### Scenario: A fixable CVE in a transitive dependency is remediated
- **GIVEN** a fixable CRITICAL/HIGH CVE in a dependency that Artemis pulls transitively (not declared directly)
- **WHEN** the build is assembled
- **THEN** the patched version is forced onto the classpath via `dependencyOverrides` so the resolved artifact is the fixed one
