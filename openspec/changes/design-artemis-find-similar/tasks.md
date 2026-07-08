# Tasks: design-artemis-find-similar (Tier 1)

Builds on the phash stored per post + the read model.

## 1. Storage

- [ ] 1.1 Store phash in the read model as a form supporting fast Hamming distance (bigint/bit(64)/bytea)

## 2. Near-duplicate query

- [ ] 2.1 (test) Hamming-distance search: within-threshold posts, closest-first, excludes self; empty when none
- [ ] 2.2 (impl) the query (XOR + bit_count / popcount) served from the read model
- [ ] 2.3 (test) reverse-image lookup: hash an arbitrary image → same query finds matches

## 3. API + UX

- [ ] 3.1 (impl) `GET /posts/{id}/similar` and a reverse-lookup endpoint (hash provided/derived)
- [ ] 3.2 Muses: a "similar" affordance on the post page (Tier 2 slots behind the same UI later)

## Future (Tier 2 — documented, not built)

- [ ] Argus emits a CLIP embedding; Artemis stores in pgvector (HNSW); cosine nearest-neighbor
- [ ] `kind: metadata` reprocess to embed the existing library
