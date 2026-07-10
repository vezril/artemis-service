# Tasks: design-artemis-related-tags

TDD throughout. An in-Artemis projection off the Post event journal.

## 1. Co-occurrence projection

- [x] 1.1 (test) PostCreated increments the post's tag pairs + `n`; TagsChanged adjusts only the diff; PostPurged decrements
      (`RelatedTagsIT`. NB: Artemis has no `PostPurged` event — the soft `PostDeleted` is the
      count-affecting delete, so the pair decrement rides on `deletePost` (re-added on `restorePost`);
      `PostCreated` carries no tags, so pairs are maintained from the `TagsChanged` diff.)
- [x] 1.2 (test) rebuild-from-journal reproduces the counts; replay idempotent
      (`RelatedTagsIT` idempotency + `PostProjectionIT` rebuild now asserts `co(gamma,rebuild)==1` after
      truncate+replay; `tag_cooccurrence` added to the rebuild truncate so replay rebuilds, not doubles.)
- [x] 1.3 (impl) sparse `tag_cooccurrence(a,b,count)` table + projection (reuse `tags.post_count` for `n`)
      (DDL table + folded into the post projection's `setTags`/`deletePost`/`restorePost` — same events,
      same current-tags read, rebuildable; no separate projection.)

## 2. Cosine query

- [x] 2.1 (test) top related by cosine `co/sqrt(n·n)`; correlated tag outranks a ubiquitous one; excludes self; empty when none
      (`RelatedTagsIT` — cat_girl (cosine 1.0) outranks 1girl (~0.55); self excluded; empty when none.)
- [x] 2.2 (impl) the query + `GET /tags/{name}/related`
      (`ReadModelRepository.relatedTags` cosine SQL + `RelatedTagsRoutes`, limit clamped; wired in Main.)

## 3. Surface (Muses)

- [ ] 3.1 related-tag quick-adds in the review queue / tag editor (stacks with Argus)
      (Muses-side UI — out of this repo; served by `GET /tags/{name}/related`.)
- [ ] 3.2 related tags in the search sidebar + per-tag page
      (Muses-side UI — out of this repo; served by the same endpoint.)

## Future (documented, not built)

- [ ] Extract to **Ariadne**: Artemis publishes `catalog.events`; Ariadne consumes + maintains
      co-occurrence externally (rebuildable from history) — if analytics grows / a shared event
      stream is wanted.
