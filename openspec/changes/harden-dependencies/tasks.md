# Tasks: harden-dependencies

Build-config only (`build.sbt`), no behavioral change. Fixed versions are confirmed against a local
Trivy scan of the built image, not assumed.

- [x] 1. (investigate) Build the image on the noble base and run
      `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest image
      --severity HIGH,CRITICAL --ignore-unfixed <image>` to capture the authoritative list of fixable
      findings. Baseline = 15 fixable HIGH (0 CRITICAL): jackson-databind (2), grpc-netty-shaded (1),
      the netty codec/handler/resolver family (10), lz4-java (1).
- [x] 2. (impl) Add `dependencyOverrides` to the `server` module in `build.sbt`, each family aligned to
      one version:
      - `com.fasterxml.jackson.core:jackson-databind` (+ the aligned jackson stack) → `2.21.4`
        (annotations tracks the minor line → `2.21`).
      - the `io.netty` family (all 16 modules on the classpath) → `4.1.135.Final` (netty-handler /
        resolver-dns need `.135`, higher than the `.133` first assumed).
      - the `io.grpc` family (grpc-netty-shaded + core/api/stub/…) → `1.75.0` (the shaded-netty
        CVE-2025-55163 fix is only in 1.75.0; no 1.67.x backport, so the whole family moves together).
      - lz4-java → `1.11.1`. The artifact actually resolved is the maintained **`at.yawk.lz4`** fork,
        not `org.lz4`; a scan proved `at.yawk.lz4:lz4-java:1.8.1` still carries CVE-2025-66566, so the
        fork is pinned (with `org.lz4:1.8.1` kept as a belt-and-suspenders no-op).
- [x] 3. (test) Full `sbt test` green — core 176 + server 253 = 429 tests, 0 failures. The gRPC client
      (`ApolloObjectClientSpec`), jackson serialization (`MediaMessagesSpec`), and persistence IT suites
      all pass under the bumps, so no binary-compat / link regression.
- [x] 4. (verify) Rebuilt `sbt server/Docker/publishLocal` and re-scanned; Trivy now reports
      **0 fixable HIGH/CRITICAL findings** on the release image.
- [ ] 5. (land) Open a PR to `development`; once merged and promoted to `main`, the release gate is
      green and the next real release (`v1.0.2+`) can be cut. *(Awaiting human authorization to push.)*
