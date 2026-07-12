# apollo-object-client Specification

## Purpose
TBD - created by archiving change adopt-lexicon-contracts. Update Purpose after archive.
## Requirements
### Requirement: Apollo client from the shared Lexicon stubs

Artemis SHALL provide an Apollo object-store gRPC client constructed from the shared
`io.codex:lexicon-grpc` `ObjectApiClient`, with no local `apollostorage.grpc` / `object_api`
codegen in the Artemis build. The connection SHALL be configured from HOCON/environment (host, port,
TLS/cleartext) so deployment can point Artemis at its Apollo instance.

#### Scenario: The client is built from the jar, not local codegen
- **WHEN** the Artemis `server` module is compiled
- **THEN** the `apollostorage.grpc.ObjectApiClient` type resolves from the `lexicon-grpc` dependency, and no `.proto` for the Apollo object API exists in the Artemis source tree

#### Scenario: The client connects per configuration
- **WHEN** the Apollo client is created with a configured host/port
- **THEN** it produces a usable `ObjectApiClient` bound to those settings (no hard-coded endpoint)

### Requirement: Streaming object transfer through the client

The Apollo client SHALL expose Artemis's two needed operations over the streamed gRPC framing:
uploading an object (`PutObject` — a header message then byte chunks, returning the stored
checksums/size) and fetching an object (`GetObject` — a header message then byte chunks). Payload
bytes SHALL be streamed, never buffered whole in memory as a precondition of the call.

#### Scenario: An object round-trips through the client
- **GIVEN** a running Apollo (or an in-process test double implementing the `lexicon-grpc` server trait)
- **WHEN** Artemis puts an object via the client and then gets it back
- **THEN** the put returns the object's checksums and size, and the get streams back the same bytes

#### Scenario: Edge case — a checksum mismatch surfaces as a typed failure
- **GIVEN** a `PutObject` whose supplied `expected_md5` does not match the streamed bytes
- **WHEN** Apollo rejects it
- **THEN** the client call fails with the gRPC error, distinguishable from a transport/connection failure (so the upload path can abort cleanly)

