# read-media-refs Specification

## Purpose
TBD - created by archiving change design-artemis-read-media-refs. Update Purpose after archive.
## Requirements
### Requirement: Search summaries expose md5 and derivative refs

The search `PostSummary` (`GET /posts`, `GET /saved-searches/{name}/results`) MUST include the
post's `md5` (when known) and a `derivatives` list of `{kind, variant}`, where `variant` is the
media object's filename. A client builds a thumbnail URL as `<base>/media/<md5>/<variant>`.

#### Scenario: An active post summary carries its media refs
- **WHEN** a search returns an active post that has processed derivatives
- **THEN** its summary includes `md5` and a `derivatives` array with each derivative's `kind` and
  `variant` filename

#### Scenario: A post without media degrades cleanly
- **WHEN** a post has no md5 or no derivatives (e.g. pending)
- **THEN** `md5` is absent/null and `derivatives` is an empty array, not an error

### Requirement: The single-post response exposes derivative refs

The `PostResponse` (`GET /posts/{id}`) MUST include a `derivatives` list of `{kind, variant}`
alongside the `md5` it already returns, so the post view can select the sample/original/transcode
variant.

#### Scenario: An active post response carries its derivatives
- **WHEN** `GET /posts/{id}` returns an active post
- **THEN** the response includes `md5` and a `derivatives` array of `{kind, variant}`

### Requirement: Variant is the gateway-relative filename

Each derivative `variant` MUST be the last path segment of the stored derivative object ref (the
name the media gateway `GET /media/{md5}/{variant}` expects), never a raw Apollo object key or a
bucket-qualified path.

#### Scenario: Variant maps to the media gateway
- **WHEN** a derivative is stored as `media/<md5[:2]>/<md5>/thumb.webp`
- **THEN** its exposed `variant` is `thumb.webp`

