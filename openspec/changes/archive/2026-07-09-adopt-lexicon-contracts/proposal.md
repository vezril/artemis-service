# Change: adopt-lexicon-contracts

> Adopt the Codex constellation's shared contract repo, **Lexicon**
> (`/Users/cference/Code/the-lexicon`), as the single source of truth for the wire contracts
> Artemis speaks — instead of hand-copying protos or defining cross-service message shapes
> locally. This is the foundation the ingest and media-gateway milestones build on.

## Why

The ingest (`4.x`) and media-gateway (`5.3`–`5.4`) tasks of `design-artemis-internals` need
Apollo's gRPC object API and the Artemis↔Hephaestus media message contract
(`ProcessMediaJob`/`MediaProcessed`/`MediaFailed`). Those contracts already exist, versioned and
published, in **Lexicon** (`io.codex:lexicon-grpc` — Apollo's gRPC stubs; `io.codex:lexicon-messages`
— the `codex.messages.v1` async messages). Consuming the published artifacts — as the sibling
services do (`apollo-storage`'s `adopt-lexicon-grpc-contracts`, and the planned Argus/Hephaestus
adoptions) — makes a producer/consumer mismatch a **build error, not a runtime surprise**, and
removes the drift risk of a hand-maintained copy. This change establishes that dependency and the
two client boundaries so the deferred ingest/media work can build on a shared, typed contract.

## What Changes

- **Build wiring** — add the GitHub Packages resolver (`vezril/the-lexicon`) with env-based
  credentials and pin exact Lexicon versions of `lexicon-grpc` + `lexicon-messages` on the `server`
  module. No Apollo/`object_api` codegen runs in Artemis; the generated stubs come from the jar.
- **apollo-object-client** (new) — a configured Apollo object-store gRPC **client** built from the
  shared `lexicon-grpc` `ObjectApiClient` (streaming `PutObject`/`GetObject`), so Artemis's upload
  path and media gateway have a typed seam to Apollo without local codegen.
- **media-message-contract** (new) — encode/decode the shared `codex.messages.v1` media messages as
  protobuf **canonical JSON** (the HermesMQ wire) via `scalapb-json4s`, parsing tolerantly
  (`ignoringUnknownFields`) for forward compatibility.
- Note: this change does **not** implement upload/consume/streaming behavior itself — that stays in
  `design-artemis-internals` `4.x`/`5.x`, now built against these shared contracts. The HermesMQ
  transport client (Hermes is not in Lexicon) is wired by the ingest change, not here.

## Capabilities

### New Capabilities
- `apollo-object-client`: a config-driven Apollo object-store gRPC client, built from the shared
  `lexicon-grpc` stubs (no local Apollo codegen), that can stream objects to and from Apollo.
- `media-message-contract`: canonical-JSON (over Hermes) encode/decode of the shared
  `codex.messages.v1` media messages, using the `lexicon-messages` types with tolerant, forward-
  compatible parsing.

### Modified Capabilities
<!-- None — no existing Artemis spec's requirements change; ingest-and-processing/catalog-api will
     consume these seams when implemented, but their requirements are unchanged. -->

## Impact

- **New dependencies:** `io.codex:lexicon-grpc` and `io.codex:lexicon-messages` (pinned), resolved
  from GitHub Packages (`https://maven.pkg.github.com/vezril/the-lexicon`) via env credentials;
  transitively pulls the pekko-grpc runtime + `scalapb-json4s`. Dev fallback: `sbt publishLocal`
  from `the-lexicon` → `~/.ivy2/local`.
- **Affected code:** `build.sbt` (`server` module — resolver, credentials, deps); new
  `server/.../grpc` (Apollo client wrapper) and `server/.../messages` (JSON codec boundary).
- **Unblocks:** `design-artemis-internals` `4.1`–`4.4` (ingest) and `5.3`–`5.4` (media gateway),
  which will consume these two seams.
- **Depends on:** Lexicon publishing `lexicon-grpc`/`lexicon-messages` to GitHub Packages (or a
  local `publishLocal`); Apollo (blobs) and HermesMQ (transport) as the runtime peers.
- **Out of scope:** the HermesMQ transport client, the actual upload/consumer/gateway logic, and any
  Artemis-authored gRPC *service* (Artemis has no gRPC consumer — Muses uses REST).
