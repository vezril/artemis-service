# Tasks: design-artemis-related-tags

TDD throughout. An in-Artemis projection off the Post event journal.

## 1. Co-occurrence projection

- [ ] 1.1 (test) PostCreated increments the post's tag pairs + `n`; TagsChanged adjusts only the diff; PostPurged decrements
- [ ] 1.2 (test) rebuild-from-journal reproduces the counts; replay idempotent
- [ ] 1.3 (impl) sparse `tag_cooccurrence(a,b,count)` table + projection (reuse `tags.post_count` for `n`)

## 2. Cosine query

- [ ] 2.1 (test) top related by cosine `co/sqrt(n·n)`; correlated tag outranks a ubiquitous one; excludes self; empty when none
- [ ] 2.2 (impl) the query + `GET /tags/{name}/related`

## 3. Surface (Muses)

- [ ] 3.1 related-tag quick-adds in the review queue / tag editor (stacks with Argus)
- [ ] 3.2 related tags in the search sidebar + per-tag page

## Future (documented, not built)

- [ ] Extract to **Ariadne**: Artemis publishes `catalog.events`; Ariadne consumes + maintains
      co-occurrence externally (rebuildable from history) — if analytics grows / a shared event
      stream is wanted.
