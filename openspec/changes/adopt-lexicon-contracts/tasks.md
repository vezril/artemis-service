# Tasks: adopt-lexicon-contracts

TDD where there's behavior (Red → Green → Refactor); the build wiring is verified by resolution +
compilation. Dependency order: build wiring → message codec (pure, fast) → Apollo client seam.

## 1. Build wiring — consume the Lexicon jars

- [x] 1.1 Add the GitHub Packages resolver (`https://maven.pkg.github.com/vezril/the-lexicon`) with
      env-based credentials (GitHub token, never committed) to the `server` module; document the
      `sbt publishLocal` dev fallback in the README/design note.
      <!-- Resolver added token-guarded (only when GITHUB_TOKEN is set); otherwise resolves from
           ~/.ivy2/local. Open question resolved: Packages is NOT the working path — publishLocal is;
           v0.3.0 published from a clean throwaway worktree (the-lexicon tree is mid-change/dirty). -->
- [x] 1.2 Pin a `lexiconVersion` and add `io.codex %% "lexicon-grpc"` + `io.codex %% "lexicon-messages"`
      to the `server` module; confirm `sbt update` resolves them (or via `publishLocal` of a pinned
      `the-lexicon` checkout) and that no `apollostorage.grpc`/`codex.messages` proto is generated locally.
      <!-- lexiconVersion = "0.3.0"; both jars on the classpath from ivy2/local; no local proto. -->
- [x] 1.3 Run `sbt evicted` / `server/compile` to verify the transitive Pekko/pekko-grpc classpath is
      single-version (Pekko 1.2.0) — no mixed-version eviction warnings.
      <!-- pekko-grpc-runtime 1.1.1 + scalapb-json4s 0.12.1 pulled transitively; no Pekko eviction. -->

## 2. Media message contract (canonical JSON over Hermes)

- [x] 2.1 (test) `MediaMessages` round-trip: `ProcessMediaJob`/`MediaProcessed`/`MediaFailed` encode to
      canonical JSON (camelCase field names, e.g. `jobId`) and decode back to an equal value.
- [x] 2.2 (test) tolerant decoding: a `MediaProcessed` JSON payload with an unknown extra field still
      decodes (forward-compatible), yielding the known fields.
- [x] 2.3 (impl) `MediaMessages` codec over `scalapb-json4s` (`JsonFormat.toJsonString` /
      `Parser().ignoringUnknownFields.fromJsonString[T]`) using the shared `codex.messages.v1` types.
      <!-- Also added `fromJsonEither` (errors-as-values) for the wire boundary — malformed/poison
           JSON returns Left rather than throwing out of a consumer stream (checker should-fix). -->

## 3. Apollo object client seam

- [x] 3.1 (test) the client is built from the `lexicon-grpc` `ObjectApiClient` and configured from
      HOCON/env (host/port) — constructs against those settings, no hard-coded endpoint, no local proto.
- [x] 3.2 (test) streaming round-trip against an in-process test double implementing the `lexicon-grpc`
      `ObjectApiPowerApi` server trait: `PutObject` (header + chunks → checksums/size) then `GetObject`
      (header + chunks → same bytes); and a `PutObject` `expected_md5` mismatch surfaces as a typed gRPC
      failure distinct from a transport error.
      <!-- Round-trip proven on the wire (HTTP/2 DATA frames); checksum-mismatch test added (checker). -->
- [x] 3.3 (impl) `ApolloObjectClient` wrapping `ObjectApiClient` behind a small put/get streaming
      interface + connection config; wire `GrpcClientSettings` from config.
