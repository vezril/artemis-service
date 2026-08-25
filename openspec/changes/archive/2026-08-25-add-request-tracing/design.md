# Design — add-request-tracing (Artemis)

## The trust boundaries, and what happens at each

Artemis has three inbound edges and two outbound. The standard's rule — *external ingress mints and
ignores the client value; a trusted internal hop adopts* — resolves each:

| Edge | Direction | Trust | Behaviour |
|------|-----------|-------|-----------|
| HTTP catalog API | in | untrusted ingress | **mint**, ignore client `X-Correlation-Id` (anti-injection) |
| Hermes-delivered message (`MediaProcessed`, `TagSuggestions`) | in | trusted internal hop | **adopt** `Message.correlation_id`; mint only if empty |
| Apollo gRPC call | out | — | **propagate** as `x-correlation-id` metadata |
| Published Hermes message (`ProcessMediaJob`) | out | — | **propagate** as `PublishRequest.correlation_id` |

### Why the HTTP edge mints (doesn't adopt)

Artemis's catalog API is ultimately reachable from the browser (via Muses/BFF). A client could forge
an `X-Correlation-Id` to poison another flow's logs, so the edge **mints its own and ignores the
inbound value** — exactly Apollo v1's posture. This is the safe default.

If we later decide the BFF is a trusted peer that forwards a genuine upstream id, this one edge can
flip from mint-ignore to adopt-if-present without touching anything else. Calling it out here so the
choice is deliberate, not accidental. **Recommendation: mint (match Apollo) for v1.**

### Why Hermes-delivered messages adopt

A `MediaProcessed` Artemis consumes is the *continuation* of an ingest that already has an id (Artemis
set it on the `ProcessMediaJob` it published; Hephaestus carried it through). Adopting it means the
asynchronous "activate the post / apply tags" work logs under the **same** id as the original ingest
request — the whole ingest→process→activate arc is one trace. This is the adopt-inbound delta Apollo
v1 doesn't have yet.

## Async propagation is the hard part

Artemis is Pekko-heavy: HTTP handlers, projections, and consumers all cross `Future`/stream
boundaries where a thread-local MDC would be lost. The MDC-propagating `ExecutionContext` (snapshot
the MDC at submit, restore on the worker, clear after — symmetric, no leak between tasks) carries the
id through those hops with no signature changes, so even deep TRACE logging stays correlated. This is
Apollo's `MdcPropagatingExecutionContext`, reused.

## Outbound propagation — the two mechanisms

- **Apollo gRPC:** add `x-correlation-id` to the request `Metadata` on the Lexicon-generated
  `ObjectApiClient`. Apollo (once its follow-on lands) adopts it; until then it's harmless.
- **Published Hermes messages:** set `PublishRequest.correlation_id` (Lexicon field) to the current
  id when publishing a `ProcessMediaJob`. HermesMQ carries it; the consumer (Hephaestus) adopts it.

Both read "the current id" from the MDC/context, so an ingest request's id flows to Apollo and onto
the job it spawns from the same source of truth.

## Non-goals

OTel spans/sampling/backend; changing catalog/ingest/projection behaviour; deciding the BFF's trust
(kept as a documented flip-point above).
