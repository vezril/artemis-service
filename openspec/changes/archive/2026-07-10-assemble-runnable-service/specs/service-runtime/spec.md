## ADDED Requirements

### Requirement: Bootable application entrypoint

The service SHALL provide a single application entrypoint (`Main`) that boots the Artemis runtime:
forming the actor system, initializing cluster sharding for the `Post` and `Pool` entities, starting
the read-model projections and the Hermes result-consume loop, loading the tag-graph cache, and
binding the HTTP surface. Startup SHALL be gated on a Postgres readiness check, and a failure to
reach a required dependency SHALL abort startup with a non-zero exit rather than serving a degraded
process.

#### Scenario: Clean boot to serving

- **WHEN** the process starts with reachable Postgres, Apollo, and HermesMQ endpoints
- **THEN** the actor system forms a cluster (of one by default), sharding is initialized, projections
  and the consume loop are running, the tag graph is loaded, and the HTTP server is bound and
  accepting requests

#### Scenario: Dependency unavailable at startup

- **WHEN** the Postgres readiness check does not pass within the configured timeout
- **THEN** startup aborts, the failure is logged, and the process exits with a non-zero status
  instead of binding the HTTP server

### Requirement: Single writer per post via cluster sharding

The service SHALL host each `Post` and `Pool` aggregate as a cluster-sharded entity keyed by its id,
so that at most one writer instance exists per id across the cluster. Command handling SHALL route to
the entity through its sharded reference rather than by spawning a fresh actor per request.

#### Scenario: Concurrent commands to one post serialize through one entity

- **WHEN** two commands for the same post id arrive concurrently, possibly on different nodes
- **THEN** both are routed to the same sharded entity instance and applied one at a time against a
  single event journal, with no competing writer

#### Scenario: HTTP write reaches the sharded entity

- **WHEN** a catalog write request is handled
- **THEN** it is dispatched to the post entity via its sharded entity reference, not a locally spawned
  actor

### Requirement: Read-model projections and result-consume loop run continuously

The service SHALL run the `Post` and `Pool` read-model projections and the Hermes media-result
consume loop as long-running background processes started at boot. The projections SHALL keep the
Postgres read tables current with the event journal, and the consume loop SHALL poll HermesMQ,
decode each result, apply it through the media-result handler, and acknowledge only on successful
processing.

#### Scenario: Event is projected to the read model

- **WHEN** an entity persists an event
- **THEN** the corresponding projection applies it to the Postgres read table so the post becomes
  queryable through the search and catalog endpoints

#### Scenario: Media result is consumed and applied

- **WHEN** a media-processing result is available on HermesMQ
- **THEN** the consume loop decodes it, applies it to the target post, and acknowledges it only after
  the handler succeeds; a handler failure leaves the message unacknowledged for redelivery

### Requirement: Tag-graph cache is loaded and refreshed

The service SHALL load the tag alias/implication graph at startup before binding HTTP, hold it in an
in-memory cache, and refresh it periodically on a configured interval. The write path
(canonicalization) and the search path (alias resolution) SHALL read the current cached snapshot.
Bounded staleness between refreshes is acceptable.

#### Scenario: Graph available before serving

- **WHEN** the HTTP server binds
- **THEN** the tag-graph cache has already been populated at least once, so the first write and the
  first search resolve aliases against a real graph

#### Scenario: Periodic refresh picks up graph changes

- **WHEN** the tag graph changes in the database and the refresh interval elapses
- **THEN** the cache is updated and subsequent canonicalization and alias resolution use the new graph

### Requirement: Composed HTTP surface

The service SHALL bind one HTTP server that composes the health/metrics routes, the search routes,
the catalog routes, and the media-gateway routes. The composition SHALL be search-first so that
fixed search paths (e.g. `/posts/facets`) are matched before the catalog's `/posts/{id}` segment
route, preserving the routing precedence proven by the route tests.

#### Scenario: Facets path is not captured by the id route

- **WHEN** a request for `GET /posts/facets` arrives at the composed server
- **THEN** it is handled by the facets endpoint, not treated as a post with id `facets`

#### Scenario: All surfaces reachable on one port

- **WHEN** the server is bound
- **THEN** health, search (`/posts`, `/posts/facets`, `/tags/autocomplete`), catalog writes and
  read-by-id, and the media-gateway routes are all served on the configured host and port

### Requirement: Graceful shutdown

The service SHALL drain gracefully on shutdown: the health endpoint SHALL report not-ready before
the HTTP server unbinds, in-flight requests SHALL be allowed to complete within a timeout, and the
cluster SHALL leave cleanly. Shutdown SHALL be wired through the actor system's coordinated-shutdown.

#### Scenario: Health flips before unbind

- **WHEN** a shutdown signal is received
- **THEN** the readiness probe begins reporting not-ready, then the HTTP server stops accepting new
  connections and drains in-flight requests before the process exits

### Requirement: End-to-end media pipeline verification

The service SHALL be covered by an end-to-end test that exercises the assembled runtime against a
real Postgres (testcontainers) with Apollo and HermesMQ doubled in-process: an upload creates a
pending post, a simulated Hephaestus `MediaProcessed` result is consumed, the post transitions to
active, and it becomes findable through the search endpoint.

#### Scenario: Upload becomes searchable after processing

- **WHEN** a media upload is submitted, then a `MediaProcessed` result for it is published to the
  in-process Hermes double
- **THEN** the post transitions from pending to active, the read model is updated, and a search query
  returns the post
