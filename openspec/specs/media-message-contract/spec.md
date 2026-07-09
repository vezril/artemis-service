# media-message-contract Specification

## Purpose
TBD - created by archiving change adopt-lexicon-contracts. Update Purpose after archive.
## Requirements
### Requirement: Shared media message types, no local copy

Artemis SHALL use the `codex.messages.v1` media message types (`ProcessMediaJob`, `MediaProcessed`,
`MediaFailed`, and their nested `ObjectRef`/`MediaMetadata`/`Derivative`/`JobError`) from the
`io.codex:lexicon-messages` dependency, pinned to an exact version, rather than defining equivalent
shapes in the Artemis source tree.

#### Scenario: The message types resolve from the shared artifact
- **WHEN** the Artemis `server` module is compiled
- **THEN** `codex.messages.v1.ProcessMediaJob` / `MediaProcessed` / `MediaFailed` resolve from the `lexicon-messages` dependency, and no equivalent `.proto` or hand-written case class for them exists in the Artemis source

### Requirement: Canonical-JSON encoding on the Hermes wire

The media messages SHALL be encoded to and decoded from protobuf **canonical JSON** (camelCase, via
`scalapb-json4s`) — the agreed HermesMQ payload format — so the bytes on the queue are the same
cross-language shape Hephaestus produces/consumes. A message SHALL round-trip
(encode → decode) to an equal value.

#### Scenario: A job round-trips through canonical JSON
- **GIVEN** a `ProcessMediaJob` with a `source` `ObjectRef` and requested derivative kinds
- **WHEN** it is encoded to canonical JSON and decoded back
- **THEN** the decoded message equals the original, and the JSON field names are camelCase (e.g. `jobId`, `postId`)

### Requirement: Tolerant, forward-compatible decoding

Decoding SHALL be tolerant of unknown fields (`ignoringUnknownFields`), so a message carrying a
field added in a newer minor contract version is still accepted by an Artemis pinned to an older
minor version (additive evolution never breaks the consumer).

#### Scenario: Edge case — an unknown field does not break decoding
- **GIVEN** a `MediaProcessed` JSON payload carrying an extra field not present in Artemis's pinned contract version
- **WHEN** Artemis decodes it
- **THEN** decoding succeeds, yielding the known fields and ignoring the unknown one (no failure)

