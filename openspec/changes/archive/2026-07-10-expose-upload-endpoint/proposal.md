# Change: expose-upload-endpoint

> Wire the built-and-tested `UploadService` to an HTTP route so a client (the Muses UI) can actually
> start an upload. The ingest spine — content-address to Apollo, seed a pending post, publish
> `ProcessMediaJob`, consume the result to `active` — runs end to end already; it just has no front
> door. This adds the one missing HTTP seam.

## Why

`UploadService.upload(bytes, contentType, mediaType)` is implemented and unit-tested (M8) and the whole
downstream pipeline is proven by `RunnableServiceE2EIT`, but `UploadService` is composed into nothing:
there is no route that accepts file bytes and calls it. So the Muses UI can browse, search, view media,
and edit (M6/M7 are served) but has **nowhere to POST an upload** — the one gap blocking the gallery's
upload feature. `POST /posts` is only a thin metadata create (`{id, md5, filetype}`), not a byte upload.

## What Changes

- **catalog-api** (modified): add an HTTP **upload endpoint** — `POST /uploads` streams the request body
  into `UploadService.upload` and returns `{ postId, status: "pending" }`. The `Content-Type` header
  carries the media MIME type; the media class (`image`/`video`) is derived from it, overridable with a
  `?mediaType=` query param. An upstream failure (Apollo/Hermes) is a clean `502`; the pipeline that
  takes the pending post to `active` is unchanged.
- `Main` constructs `UploadService` (Apollo uploader + Hermes publisher + the sharded post factory) and
  composes the new route into the HTTP surface.

## Impact

- **Affected code:** a new `UploadRoutes` (parameterized over the upload function, testable without
  Apollo/Hermes), an `UploadResponse` JSON, and the `Main` wiring (`ApolloObjectUploader` +
  `HermesMediaJobPublisher` + `UploadService`, route composed in).
- **Depends on:** the existing `ingest-and-processing` capability (`UploadService`, the Apollo/Hermes
  adapters) and the M9 runtime assembly — all already built.
- **Unblocks:** the Muses UI upload feature (the `POST` the "upload" button needs).
- **Out of scope:** multipart/form-data parsing (raw binary body is simpler and streams cleanly),
  auth, resumable/chunked uploads, and client-side md5 pre-checks — the md5 is computed server-side.
