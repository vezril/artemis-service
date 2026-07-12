# Change: add-static-analysis

> Tighten CI static analysis — coverage, CI-enforced scalafix, stricter Scala 3 warnings, and security
> scanning (secrets + dependency + image CVEs) — on top of the existing scalafmt / scalafix / -Werror
> gates. Part of a constellation-wide effort (see `codex/docs/static-analysis.md`).

## Why

The build already enforces formatting (scalafmt), unused/strict compile (`-Wunused:all -Werror`), and
has scalafix *configured* — but coverage is untracked, scalafix isn't a CI gate, the Scala 3 warning
set isn't cranked, and nothing scans for committed secrets, vulnerable dependencies, or CVEs in the
shipped image. This closes those gaps with native, serverless CI steps.

Not SonarQube: it's Java-first, and for Scala it merely dashboards Scapegoat/scoverage (the native
tools do the analysis) while being a RAM-heavy server. Scapegoat itself is skipped — its Scala 3
support is shaky; the Scala-3-native path is cranked `-W` flags + scalafix + scoverage.

## What Changes

- **static-analysis** (new): CI gains coverage reporting (scoverage), a CI-enforced `scalafixAll
  --check`, an expanded scalac `-W` set, a secret scan (gitleaks), a dependency-update/CVE feed
  (Dependabot), and an image CVE scan (Trivy) on the published Docker image.

## Impact

- `project/plugins.sbt`: + `sbt-scoverage`. `build.sbt`: enable SemanticDB (for scalafix) + additional
  `-W` flags under `-Werror`, added **incrementally** (cranking `-Werror` can surface findings in
  existing code — fix or scope per flag, don't blanket-suppress).
- `.github/workflows/ci.yml`: a coverage step (report; gate later via `coverageMinimumStmtTotal`), a
  `scalafixAll --check` step, and a gitleaks job.
- Release/dev workflow: a Trivy scan of the built image. `.github/dependabot.yml`: sbt +
  github-actions ecosystems.
- No runtime/behavioral change; existing tests unaffected. Mirrors the `new-scala-service` skill
  (future services get scoverage + gitleaks by default).
- **Out of scope:** SonarQube / a self-hosted dashboard (deferred), and Scapegoat (Scala 3 support).
