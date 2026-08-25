# Tasks — design-artemis-read-media-refs

## 1. DTO + codec
- [x] 1.1 Add `DerivativeRef(kind: String, variant: String)` DTO + spray-json format (shared by SearchJson + PostJson).
- [x] 1.2 `SearchJson.PostSummary`: add `md5: Option[String]` and `derivatives: List[DerivativeRef]`; update its jsonFormat.
- [x] 1.3 `PostJson.PostResponse`: add `derivatives: List[DerivativeRef]`; update its jsonFormat.

## 2. Populate from search (projection)
- [x] 2.1 Find the search row→PostSummary mapping; add `md5` and `derivatives` from the projection `posts` row (`md5`, `derivatives` JSON — reuse the existing derivatives parsing, e.g. `ReadModelRepository.derivativeObjectKeys` or the JSON shape) and derive `variant` = last `/`-segment of each derivative ref, with `kind`.
- [x] 2.2 Ensure the SELECT includes `md5` and `derivatives` if not already projected into the row used for summaries.

## 3. Populate from the aggregate (entity read)
- [x] 3.1 `PostJson.active(...)`: map `PostMedia.derivatives` (domain `Derivative(kind, ref)`) into `DerivativeRef(kind, variant)` where `variant` = last `/`-segment of `ref`.

## 4. Tests
- [x] 4.1 Search spec/IT: an active post summary includes `md5` + non-empty `derivatives` with correct `{kind, variant}`; a media-less post has null md5 + empty derivatives.
- [x] 4.2 Catalog route/JSON test: `GET /posts/{id}` for an active post includes `derivatives` with the right `variant` filenames; a pending post has empty derivatives.
- [x] 4.3 A unit assertion that `variant` is the last path segment (e.g. `media/ab/abc/thumb.webp` → `thumb.webp`).

## 5. Verify
- [x] 5.1 `-Werror` compile / scalafmt / scalafix / full `server/test` green.
- [x] 5.2 `openspec validate design-artemis-read-media-refs --strict`.
