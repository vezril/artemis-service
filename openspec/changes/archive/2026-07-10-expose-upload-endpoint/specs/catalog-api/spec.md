## ADDED Requirements

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
