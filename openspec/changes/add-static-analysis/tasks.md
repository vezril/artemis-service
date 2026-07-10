# Tasks: add-static-analysis

> Bootstraps the CI foundation (no `.github/` existed) and layers static-analysis tightening on top.
> Build-level + config pieces are locally verified; CI goes green once repo secrets are set.

## 1. Build-level analysis (locally verified)

- [x] 1.1 Add `sbt-scoverage` to `project/plugins.sbt` (coverage reporting; ungated).
- [x] 1.2 Enable SemanticDB in `build.sbt` (`semanticdbEnabled`, `semanticdbVersion`) so scalafix's
      semantic rules run in CI.
- [x] 1.3 Crank scalac flags under `-Werror`: add `-Werror`, `-Wnonunit-statement`, keep
      `-Wvalue-discard`; strip `-Wnonunit-statement`/`-Wvalue-discard` from `Test` scope (sbt
      delegates Compile→Test; ScalaTest matchers return `Assertion`).
- [x] 1.4 Resolve the findings the cranked flags surface (production `val _ =` discards; unused
      `SearchRoutes` implicit + unused test imports removed) — fixed, not blanket-suppressed.
- [x] 1.5 Make the Lexicon resolver honor `LEXICON_TOKEN` (read:packages PAT) besides `GITHUB_TOKEN`,
      so CI can resolve `io.codex:lexicon-*` once the secret is set (mirrors apollo).

## 2. scalafix as a gate (locally verified)

- [x] 2.1 Confirm `.scalafix.conf` (DisableSyntax + OrganizeImports) and run `scalafixAll --check`
      as the gate; apply OrganizeImports auto-fixes.
- [x] 2.2 Handle legit interop nulls: idiomatic `Option(...)` where clean (Jackson JsonNode), else a
      `// scalafix:ok DisableSyntax.null` line suppression (testkit guards, reactive Mono bridge).

## 3. CI foundation + static-analysis workflows (authored; secret-gated)

- [x] 3.1 `.github/actions/setup-scala` composite action (Temurin 21 + sbt + Coursier/sbt cache).
- [x] 3.2 `.github/workflows/ci.yml`: `format` (scalafmt), `lint` (`scalafixAll --check`),
      `build-test` (compile + test + `coverage`/`coverageAggregate`, report uploaded),
      `secrets` (gitleaks), `version-check` (dynver snapshot sanity).
- [x] 3.3 `.github/workflows/dev.yml` (dev image on push to development) and `release.yml`
      (semver tag → test → Trivy image scan → publish X.Y.Z + latest).
- [x] 3.4 `.github/dependabot.yml`: `sbt` + `github-actions` ecosystems, weekly.

## 4. Verify

- [x] 4.1 Local gate green: compile under the cranked flags, `scalafixAll --check`,
      `clean coverage test coverageAggregate` (report produced), and the full test suite
      (core 131 + server; the `PostProjectionIT` cross-suite flake passes isolated).
- [ ] 4.2 (owner action — cannot be done from code) Set repo secrets so CI/publish go green:
      `LEXICON_TOKEN` (read:packages PAT — required for every sbt job to resolve deps),
      `DOCKERHUB_USERNAME` + `DOCKERHUB_TOKEN` (dev/release publish + Trivy). Document in the PR.
- [ ] 4.3 (deferred) Add a `coverageMinimumStmtTotal` floor once the suite is judged mature.
