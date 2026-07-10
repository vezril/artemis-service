# static-analysis

## ADDED Requirements

### Requirement: Test coverage is measured in CI
CI SHALL produce a statement-coverage report for the build via scoverage. A coverage floor
(`coverageMinimumStmtTotal`) MAY be enforced once the suite is mature; until then coverage is reported,
not gated, so an immature suite does not block the build.

#### Scenario: Coverage is reported on a build
- **WHEN** CI runs the test job
- **THEN** a statement-coverage report is produced, and if a floor is configured the build fails when coverage is below it

### Requirement: scalafix is enforced in CI
CI SHALL run `scalafixAll --check` so the configured rules (DisableSyntax, OrganizeImports) act as a
gate rather than being merely available; a violation SHALL fail the build. SemanticDB SHALL be enabled
so semantic rules can run.

#### Scenario: A scalafix violation fails the build
- **WHEN** code violates a configured scalafix rule (e.g. a disallowed `null`)
- **THEN** the CI scalafix check fails

### Requirement: Stricter Scala 3 compiler warnings
The build SHALL enable an expanded scalac `-W` set beyond `-Wunused:all` (e.g. `-Wvalue-discard`,
`-Wnonunit-statement`) under `-Werror`, added incrementally so each new flag's findings are resolved
rather than blanket-suppressed.

#### Scenario: A newly-flagged warning is an error
- **GIVEN** an enabled `-W` flag under `-Werror`
- **WHEN** code triggers that warning
- **THEN** compilation fails

### Requirement: Secret scanning
CI SHALL scan the repository (and its history) for committed secrets with gitleaks and SHALL fail the
build on a finding.

#### Scenario: A committed secret fails the build
- **WHEN** a credential is present in the repository or its history
- **THEN** the secret-scan job fails

### Requirement: Dependency and image vulnerability scanning
The project SHALL receive dependency-update / CVE alerts via Dependabot (sbt + github-actions
ecosystems), and the release pipeline SHALL scan the published Docker image for known vulnerabilities
with Trivy.

#### Scenario: The image is scanned on release
- **WHEN** the release workflow builds the service image
- **THEN** the image is scanned for known CVEs and the result is surfaced, failing on the configured severities
