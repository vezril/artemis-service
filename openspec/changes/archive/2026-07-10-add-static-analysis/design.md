# Design: add-static-analysis

## Context

The proposal assumed static-analysis steps would be layered onto an existing CI pipeline. On
inspection artemis-service had **no `.github/` at all** — no workflows, no Dependabot, and no
SemanticDB/scoverage wiring — while the sibling `apollo-storage` carries the house template
(`setup-scala` composite action + `ci.yml`/`dev.yml`/`release.yml`, SemanticDB enabled). So this
change also **bootstraps the CI foundation** (porting apollo's template) and layers the
static-analysis tightening on top. Reference: `/Users/cference/Code/apollo-storage/.github/` and its
`build.sbt` scalac/semanticdb block.

## Goals / Non-Goals

**Goals:** coverage reporting (scoverage, ungated); scalafix as a CI gate (SemanticDB-backed);
cranked `-W` flags under `-Werror`; secret scanning (gitleaks); dependency/CVE feeds (Dependabot +
Trivy image scan); and the base CI (ci/dev/release + setup-scala) needed to host them.

**Non-Goals:** SonarQube / a self-hosted dashboard (deferred); Scapegoat (shaky Scala 3 support); a
coverage floor (`coverageMinimumStmtTotal`) — report only until the suite matures; a docs-verify job
(apollo has one; out of static-analysis scope here).

## Decisions

- **Mirror apollo's CI template.** `setup-scala` composite action (Temurin 21 + sbt + Coursier/sbt
  cache); `ci.yml` jobs `format` / `lint` (scalafix `--check`) / `build-test` (compile + test +
  coverage) / `secrets` (gitleaks) / `version-check`; `dev.yml` (dev image on push to development);
  `release.yml` (semver tag → test → Trivy → publish). Keeps the constellation consistent.
- **Cranked flags.** `-Werror` + `-Wnonunit-statement` + `-Wvalue-discard` in the base
  `scalacOptions`, plus `semanticdbEnabled`/`semanticdbVersion` for scalafix. Because sbt delegates
  `Compile → Test`, `-Wnonunit-statement` / `-Wvalue-discard` are stripped from `Test` scope
  (`Test / scalacOptions --= …`) — ScalaTest matchers return `Assertion`, which would trip them in
  test sources. Findings in production code were fixed (explicit `val _ =` discards), not suppressed.
- **scalafix gate.** `scalafixAll --check` runs the configured `DisableSyntax` + `OrganizeImports`.
  Legitimate Java-interop nulls (testkit guards, the reactive `Mono`→`Future` bridge, a Jackson
  `JsonNode` check) are handled by the idiomatic `Option(...)` where clean, else a
  `// scalafix:ok DisableSyntax.null` line suppression (matching apollo's pattern).
- **scoverage ungated.** `sbt clean coverage test coverageAggregate` produces the report (uploaded as
  a CI artifact); no floor yet.
- **Lexicon resolution in CI.** artemis resolves `io.codex:lexicon-*` from `~/.ivy2/local` locally
  ("GitHub Packages isn't the working path" so far). The build's resolver now also honors
  `LEXICON_TOKEN` (a read:packages PAT, preferred) besides `GITHUB_TOKEN`, mirroring apollo — so the
  workflows can resolve deps **once that repo secret is set**. Without it, every sbt CI job fails at
  dependency resolution: this is the one piece the workflows can't self-verify locally.

## Risks / Trade-offs

- **[CI can't compile until `LEXICON_TOKEN` is set]** → the resolver + workflows are authored and
  correct, but red until the secret (a repo-settings action only the owner can do) exists. Flagged in
  the README/PR; the local build + gates are fully green in the meantime.
- **[`dev`/`release`/Trivy need `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`]** → same: authored, gated on
  secrets. The image itself already builds locally (M9).
- **[Cranking `-Werror` surfaces existing warnings]** → applied incrementally; ~14 discarded-value
  and a few unused-symbol findings were fixed test-first, full suite re-verified green.
- **[Coverage + testcontainers under forked tests]** → scoverage 2.x supports Scala 3 + forked; the
  report generated locally (91%+ on core). Ungated so an instrumentation hiccup never blocks a merge.

## Migration Plan

Additive. `build.sbt`/`plugins.sbt` changes + null fixes land first (locally verified: compile under
the cranked flags, `scalafixAll --check`, `coverage … coverageAggregate`, full test suite). The
`.github/` tree is authored alongside. CI goes green once `LEXICON_TOKEN` (and, for publish/scan,
`DOCKERHUB_*`) repo secrets are set — no code change needed then.

## Open Questions

- When to add a `coverageMinimumStmtTotal` floor (once the suite is judged mature).
- Whether to port apollo's docs-verify job + `scripts/verify-docs.sh` later (out of scope now).
