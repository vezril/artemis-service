# Design: adopt-lexicon-contracts

## Context

Artemis's ingest and media-gateway work needs two cross-service contracts: Apollo's gRPC object API
(to stream originals in and derivatives out) and the Artemis↔Hephaestus media messages
(`ProcessMediaJob`/`MediaProcessed`/`MediaFailed`, carried over HermesMQ). Both already exist as
versioned, published artifacts in **Lexicon** (`/Users/cference/Code/the-lexicon`), the
constellation's shared-contract repo:

- `io.codex:lexicon-grpc` — pekko-grpc stubs generated from `apollostorage/grpc/object_api.proto`
  (messages, `ObjectApiPowerApi` server trait + handler, **`ObjectApiClient`**, service descriptors).
- `io.codex:lexicon-messages` — the `codex.messages.v1` async messages as ScalaPB case classes, with
  `scalapb-json4s` for protobuf canonical JSON (the HermesMQ wire).

The sibling `apollo-storage` already consumes `lexicon-grpc` (its `adopt-lexicon-grpc-contracts`
change) — as the *server* implementing the trait. Artemis is the mirror: a *client* of the Apollo
service and a producer/consumer of the media messages. This change wires the dependency and the two
thin boundaries; it does not implement upload/consume/gateway behavior (that stays in
`design-artemis-internals` 4.x/5.x).

## Goals / Non-Goals

**Goals:**
- Consume the shared contracts as **pinned published jars**, with no `object_api`/message protos or
  hand-written copies in the Artemis tree (single source of truth; drift becomes a build error).
- A config-driven Apollo `ObjectApiClient` seam (`PutObject`/`GetObject` streaming).
- A canonical-JSON codec seam for the media messages, with tolerant/forward-compatible parsing.
- Fast, infra-light verification: JSON round-trip is a pure unit test; the gRPC client round-trip
  uses an in-process test double implementing the `lexicon-grpc` server trait (no live Apollo).

**Non-Goals:**
- The HermesMQ transport client (Hermes is not in Lexicon — its gRPC client is wired by the ingest
  change), the actual upload/consumer/media-gateway logic, and any Artemis-authored gRPC *service*.

## Decisions

- **Depend on the jars; run no Apollo codegen in Artemis.** `lexicon-grpc` already carries the
  generated `ObjectApiClient`, so Artemis adds the dependency and constructs the client via
  `GrpcClientSettings` — it does *not* enable the pekko-grpc plugin for the Apollo proto. *Alternative
  rejected:* copying `object_api.proto` into Artemis and generating locally — reintroduces the drift
  Lexicon exists to remove.
- **Resolve from GitHub Packages, pin an exact version.** Add the `vezril/the-lexicon` Maven resolver
  with env-based credentials (a GitHub token, same pattern as the siblings' publish), and pin an
  exact `lexiconVersion`. *Dev fallback:* `sbt publishLocal` from `the-lexicon` populates
  `~/.ivy2/local`, so a contributor without Packages access can still resolve. Document both.
- **Canonical JSON via `scalapb-json4s`, tolerant parsing.** Encode with `JsonFormat.toJsonString`,
  decode with `new Parser().ignoringUnknownFields.fromJsonString[T]`. Matches Lexicon's stated wire
  (camelCase canonical JSON, byte-identical cross-language) and its forward-compatibility guidance.
  *Alternative rejected:* binary protobuf on the queue — Lexicon deliberately chose readable JSON so
  queue payloads are debuggable.
- **Thin, well-typed seams.** `ApolloObjectClient` wraps `ObjectApiClient` behind Artemis's own small
  interface (put/get streaming); a `MediaMessages` object owns the JSON encode/decode. Keeps the
  ingest/gateway code decoupled from generated-stub specifics and easy to test-double.
- **Pekko version alignment.** Lexicon pins Pekko `1.2.0` / pekko-grpc `1.1.1` to match the siblings;
  Artemis is already on Pekko `1.2.0`, so the transitive classpath stays single-version (Pekko forbids
  a mixed-version classpath) — verify on `sbt update`.

## Risks / Trade-offs

- **[Lexicon artifacts may not be published to GitHub Packages yet]** → the design supports the
  `publishLocal` fallback; the first task verifies resolution and documents whichever path works. If
  Packages auth is unavailable in CI, CI uses `publishLocal` of a pinned Lexicon checkout.
- **[Transitive Pekko/pekko-grpc version skew]** → pin `lexiconVersion` to a build known to target
  Pekko 1.2.0; `sbt evicted`/`update` in the wiring task catches a mismatch early.
- **[Credentials handling]** → the GitHub token is env-only (never committed), mirroring the siblings'
  publish credentials; the resolver reads it from the environment.
- **[Contract drift on Lexicon minor bumps]** → tolerant parsing absorbs additive changes; a major
  bump is an explicit, reviewed version pin change. Field numbers are never reused (Lexicon policy).

## Migration Plan

Additive only — no existing Artemis behavior changes. Steps: (1) add resolver + credentials + pinned
deps to the `server` module; (2) confirm `lexicon-grpc`/`lexicon-messages` resolve and compile
(publishLocal fallback if needed); (3) add the `ApolloObjectClient` seam + config; (4) add the
`MediaMessages` JSON codec seam. Rollback = revert the dependency and the two small seams; nothing
else consumes them until the ingest/gateway milestones land.

## Open Questions

- Is `lexicon-grpc`/`lexicon-messages` `0.1.0` actually pushed to `vezril/the-lexicon` Packages, or is
  `publishLocal` the current reality? (Resolved empirically by the first task.)
- Exact `lexiconVersion` to pin (depends on the latest tagged Lexicon release).
