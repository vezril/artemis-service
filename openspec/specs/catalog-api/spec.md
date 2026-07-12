# catalog-api Specification

## Purpose
TBD - created by archiving change design-artemis-internals. Update Purpose after archive.
## Requirements
### Requirement: Read endpoints served from projections

Artemis SHALL serve the read endpoints from the projection read models: `GET /posts` with a
DSL `tags` query, `order`, and keyset `cursor` (never OFFSET); `GET /posts/{id}` for post
detail; `GET /tags/autocomplete` (trigram, grammar-aware); pool reads; and **tag facets for
a query** (the tags occurring across the matching posts, grouped by category with a count
per tag) to power the gallery's tags-in-results panel. `GET /posts/{id}` MAY consult the
entity for read-your-writes freshness.

#### Scenario: Search returns a keyset page
- **GIVEN** a query `1girl cat_ears order:score`
- **WHEN** `GET /posts` is called
- **THEN** it returns a page of matching post summaries ordered by score with a next `cursor`, sourced from the read model

#### Scenario: Tag facets for a query
- **GIVEN** a query matching a set of posts
- **WHEN** the facets endpoint for that query is called
- **THEN** it returns the tags occurring across the matching posts, grouped by category, each with a count of how many matches carry it

#### Scenario: Edge case — autocomplete is context-aware
- **GIVEN** `GET /tags/autocomplete?q=rating:&context=metatag`
- **WHEN** it is served
- **THEN** it returns the rating enum values rather than tag names

### Requirement: Write endpoints as entity commands

Artemis SHALL expose writes that translate to entity commands: `POST /posts` (upload),
`PATCH /posts/{id}/tags`, `POST/DELETE /posts/{id}/favorite`, `POST /posts/{id}/score`,
and pool mutations (`POST /pools`, `POST /pools/{id}/posts`, reorder). A write SHALL be
acknowledged once its event is durably journaled.

#### Scenario: A tag edit is applied via a command
- **GIVEN** `PATCH /posts/{id}/tags` with a new tag set
- **WHEN** it is handled
- **THEN** it issues `ChangeTags` to the entity and returns success once `TagsChanged` is journaled (canonicalized)

#### Scenario: Edge case — a write to a missing post returns 404
- **GIVEN** a `PATCH /posts/{id}/tags` for a non-existent id
- **WHEN** it is handled
- **THEN** it returns `404` and journals no event

### Requirement: HTTP media gateway over Apollo

Artemis SHALL expose an HTTP media endpoint (e.g. `GET /media/{md5}/{variant}`) that streams
the corresponding Apollo derivative (thumb/sample/original/transcode) to the browser,
supporting HTTP range requests for video seeking. Browsers SHALL never need to speak gRPC.

#### Scenario: A thumbnail is served over HTTP
- **GIVEN** a post with a stored thumbnail derivative in Apollo
- **WHEN** the browser requests `GET /media/<md5>/thumb.webp`
- **THEN** Artemis streams the bytes from Apollo over HTTP with the correct content type

#### Scenario: Edge case — video honors range requests
- **GIVEN** a video transcode in Apollo
- **WHEN** the browser issues a ranged `GET` while seeking
- **THEN** Artemis returns `206 Partial Content` for the requested byte range

### Requirement: HTTP upload endpoint

The service SHALL expose an HTTP endpoint that accepts a media file's bytes and starts the ingest
pipeline: `POST /uploads` streams the request body into the upload write path (content-address to
Apollo, seed a pending post, publish a processing job) and responds with the new post's id and its
initial `pending` status. The request `Content-Type` header SHALL carry the media MIME type; the media
class (e.g. `image`, `video`) SHALL be derived from it and MAY be overridden by a `mediaType` query
parameter. A failure of an upstream dependency (the object store or the message broker) SHALL surface
as a `502` rather than a success or an ambiguous `500`.

#### Scenario: An upload creates a pending post

- **WHEN** a client sends `POST /uploads` with the media bytes as the body and a `Content-Type` of the
  media MIME type
- **THEN** the service stores the object, seeds a pending post, publishes a processing job, and responds
  `201 Created` with `{ postId, status: "pending" }`

#### Scenario: The media class is derived from the content type

- **WHEN** the request `Content-Type` is `video/mp4` and no `mediaType` query parameter is given
- **THEN** the processing job is published with the media class `video`

#### Scenario: Edge case — an upstream dependency failure is a 502

- **WHEN** the object store or message broker fails while handling the upload
- **THEN** the endpoint responds `502` with an error message, and no success is reported to the client

