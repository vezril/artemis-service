# Design: expose-upload-endpoint

## Context

Every ingest stage exists and is tested; only the HTTP trigger is missing. `UploadService.upload(
bytes: Source[ByteString], contentType: String, mediaType: String): Future[UploadResult]` buffers the
stream to compute the md5, content-addresses the original to Apollo, seeds a pending post via the
sharded entity, and publishes a `ProcessMediaJob`. It's wired to nothing. Mirror the codebase's
seam-per-route pattern (`CatalogRoutes`, `MediaRoutes`, `SearchRoutes` all take injected functions).

## Goals / Non-Goals

**Goals:** one `POST` route that streams the body into `UploadService.upload` and returns the new
post's id + pending status; `Main` constructs the real `UploadService` and composes the route.

**Non-Goals:** multipart/form-data, auth, resumable/chunked uploads, client md5 verification, a
gallery-side progress protocol.

## Decisions

- **`POST /uploads`, raw binary body.** A distinct path (not `POST /posts`, which is the thin
  metadata create). The request body IS the media bytes; `Content-Type` carries the MIME type. Raw
  binary streams cleanly (no multipart envelope to parse) and a JS client sends it trivially
  (`fetch(url, { method:'POST', body: file, headers:{'Content-Type': file.type} })`). *Alternative
  rejected:* multipart/form-data — more browser-idiomatic for `<form>`s but adds parsing and buffers
  the envelope; not worth it for a JSON-client gallery at personal scale.
- **Media class derived from the MIME, overridable.** `mediaType` (Hephaestus's routing class) is
  derived from the content type — `image/*` → `image`, `video/*` → `video`, else the top-level
  segment — with a `?mediaType=` query override for anything the heuristic misses.
- **`UploadRoutes` is a seam.** It takes `upload: (Source[ByteString, ?], String, String) =>
  Future[UploadResult]`, so it route-tests against a fake with no Apollo/Hermes (like the sibling
  routes). `Main` passes `uploadService.upload`.
- **Errors.** Success → `201 Created { postId, status:"pending" }`. `UploadService.upload` fails only
  when a downstream (Apollo put / Hermes publish) fails — an upstream-dependency error, so → `502`
  with the message. The md5 is server-computed, so there is no client-checksum 4xx path here.
- **Streaming/materializer.** `UploadService` runs the fold on its own `system` materializer, so the
  route just hands it `entity.dataBytes` — no materializer needed in the route.

## Risks / Trade-offs

- **[Buffering the whole body to compute md5]** → `UploadService` already does this (accepted at
  personal scale, documented there); large videos hold a full copy in memory transiently. Out of
  scope to change here.
- **[Raw body vs multipart]** → if Muses later wants multipart or multiple files per request, add a
  second route; this one stays the simple single-object path.

## Migration Plan

Additive — a new route + `Main` wiring, no change to `UploadService` or the pipeline. Rollback = don't
compose the route.
