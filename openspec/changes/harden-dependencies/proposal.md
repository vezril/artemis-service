# Change: harden-dependencies

> Pin patched versions of the transitively-pulled JVM libraries that carry known fixable HIGH/CRITICAL
> CVEs so the release pipeline's Trivy gate passes. Dependency versions only; no source or behavioral
> change.

## Why

The release workflow (`.github/workflows/release.yml`) fails the Trivy image scan
(`severity: CRITICAL,HIGH`, `ignore-unfixed: true`, `exit-code: 1`), which blocks publishing the
image and cutting a GitHub Release. The base-image finding (the Ubuntu `pebble` Go binary) was already
resolved by pinning `dockerBaseImage := "eclipse-temurin:21-jre-noble"` (PR #43). What remains are
fixable HIGH CVEs (0 CRITICAL) in Java dependencies that Artemis does not declare directly — they are
pulled transitively under `pekko-serialization-jackson`, `pekko-grpc` / the Codex gRPC clients, and
Kafka-adjacent codecs:

- `com.fasterxml.jackson.core:jackson-databind` — arbitrary code execution via a
  `PolymorphicTypeValidator` bypass; pulled by `pekko-serialization-jackson`.
- the `io.netty` codec/handler/resolver family — multiple HIGH; transitive under the gRPC stack.
- `io.grpc:grpc-netty-shaded` — bundled netty carrying the same class of finding.
- `lz4-java` — a HIGH decompression finding. (The artifact actually on the classpath is the
  maintained `at.yawk.lz4` fork, so the fork coordinate is what gets pinned, not `org.lz4`.)

Because these are transitive, bumping our direct deps is not enough; the fixed versions must be forced
onto the classpath with `dependencyOverrides`. This change encodes that remediation as living build
config so the release gate is green and stays green (Dependabot keeps the overrides moving forward).

## What Changes

- **static-analysis** (modified): the existing "Dependency and image vulnerability scanning"
  requirement is strengthened — the release image SHALL carry **no fixable CRITICAL/HIGH CVEs** (the
  Trivy gate passes, not merely "surfaces the result"), and fixable CVEs in transitive dependencies
  SHALL be remediated by pinning patched versions via `dependencyOverrides`.

Implementation (`build.sbt`, `server` module — versions confirmed against a local Trivy scan of the
built image):

- add `dependencyOverrides` forcing the patched versions of `jackson-databind`, the `io.netty`
  codec/handler/resolver artifacts, `grpc-netty-shaded`, and `lz4-java` (plus any other jar the scan
  flags non-zero).
- keep the overrides Pekko-classpath-safe: the netty family is bumped as a set to a single aligned
  version, and the versions chosen are the nearest patch releases so `pekko-grpc` binary compatibility
  is preserved.

## Impact

- `build.sbt` only — no application source, API, config, or behavioral change.
- **Binary-compat risk:** netty/grpc live under `pekko-grpc`; a bad bump could break at link/runtime.
  Mitigated by running the **full `sbt test` suite** (which exercises the gRPC clients and the
  persistence/serialization paths) and rebuilding + re-scanning the image until Trivy reports zero
  fixable HIGH/CRITICAL.
- Unblocks: publishing the release image and cutting the next real release (`v1.0.2+`; the existing
  `v1.0.0`/`v1.0.1` tags published nothing).
- Out of scope: the base-image pin (done in PR #43), unfixed CVEs (`ignore-unfixed`), and any change
  to the scan configuration itself.
