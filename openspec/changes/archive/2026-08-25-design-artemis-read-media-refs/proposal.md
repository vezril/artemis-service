## Why

The read API returns no way to locate a post's media. `GET /posts` (search `PostSummary`) omits
`md5` entirely, and `GET /posts/{id}` (`PostResponse`) returns `md5` but not which derivative
variants exist. The media gateway is `GET /media/{md5}/{variant}` — so a client (the Artemis UI
gallery + post view) cannot build a single thumbnail or media URL from what the read API exposes,
and guessing Hephaestus's variant filenames is exactly the coupling the codebase avoids elsewhere.
This change exposes the media references the read API already has the data for.

## What Changes

- Add `md5` and a `derivatives` list to the search **`PostSummary`** (`GET /posts`,
  `GET /saved-searches/{name}/results`) so a results gallery can build thumbnail URLs.
- Add the same `derivatives` list to **`PostResponse`** (`GET /posts/{id}`) — `md5` is already
  present — so the single-post view can pick the sample/original/transcode variant.
- Each derivative is exposed as `{kind, variant}` where `variant` is the media object's filename
  (the last path segment of the stored derivative ref, e.g. `thumb.webp`, `sample.webp`,
  `720p.mp4`). A client builds the URL as `<base>/media/<md5>/<variant>`.
- Both are sourced from data the read model / aggregate already holds (the projection `posts` row
  has `md5` + `derivatives`; the `PostMedia` aggregate state has its derivatives) — no new events,
  no schema change, no Hephaestus coupling in the client.

## Capabilities

### New Capabilities
- `read-media-refs`: the read API exposes each post's `md5` and its derivative `{kind, variant}`
  refs (on both the search summary and the single-post response) so clients can construct media
  gateway URLs without guessing storage layout.

### Modified Capabilities
- (none at the requirement level — additive fields on existing responses; the field additions are
  captured as the new capability's requirements.)

## Impact

- **Response DTOs:** `PostSummary` (`SearchJson`) gains `md5: Option[String]` and
  `derivatives: List[DerivativeRef]`; `PostResponse` (`PostJson`) gains
  `derivatives: List[DerivativeRef]`. New `DerivativeRef(kind, variant)` DTO + codec.
- **Sources:** search maps them from the projection `posts` row (`md5`, `derivatives` JSON — the
  same columns the purge/orphan paths read); the entity read maps them from `PostMedia.derivatives`.
  `variant` = the last `/`-segment of the stored `<bucket>/<object>` derivative ref.
- **Backwards-compatible:** purely additive JSON fields; existing clients ignore them.
- **Unblocks:** the Artemis UI catalog read surface (`design-artemis-ui-catalog-read`).

## Non-goals

- Signed/absolute media URLs (the client prepends its configured base); pagination or search
  behavior changes; exposing raw Apollo object keys (only the gateway-relative `variant`).
