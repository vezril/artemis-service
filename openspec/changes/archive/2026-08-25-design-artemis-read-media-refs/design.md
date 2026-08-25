## Context

The media gateway is `GET /media/{md5}/{variant}`, but the read API exposes neither the variant
filenames nor (for search) the md5 — so a client can't build a media URL. The data exists: the
projection `posts` row has `md5` + a `derivatives` JSON column, and the `PostMedia` aggregate state
holds `Derivative(kind, ref)` values. This change projects that into the read responses.

## Goals / Non-Goals

**Goals:** let a client build thumbnail + media URLs from `GET /posts`, `GET /posts/{id}`, and
`GET /saved-searches/{name}/results` without guessing storage layout.

**Non-Goals:** absolute/signed URLs (the client prepends its base), exposing raw Apollo keys, any
search/pagination behavior change.

## Decisions

- **Expose `{kind, variant}`, not object keys.** `variant` is the last `/`-segment of the stored
  `<bucket>/<object>` derivative ref — exactly the token the media gateway expects — so the client
  builds `<base>/media/<md5>/<variant>`. The raw Apollo key stays internal.
- **Additive, source-of-truth-preserving.** Search maps from the projection row (same `md5` +
  `derivatives` columns the purge/orphan paths already read); the entity read maps from
  `PostMedia.derivatives`. No new events, no schema change.
- **Absent media is empty, not error.** A pending/media-less post yields `md5=null`,
  `derivatives=[]`.

## Risks / Trade-offs

- **Two mapping sites** (search projection + entity `active(...)`) must agree on the `variant`
  derivation; covered by a shared derivation helper + tests on both paths.
- The client still needs to know which `kind` to render (thumb vs sample vs original); that mapping
  lives in the UI, but it now has the real list to choose from instead of guessing.
