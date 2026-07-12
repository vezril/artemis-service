# Tasks: design-artemis-find-similar (Tier 1)

Builds on the phash stored per post + the read model.

## 1. Storage

- [x] 1.1 Store phash in the read model as a form supporting fast Hamming distance (bigint/bit(64)/bytea)
      DEVIATED — no storage change. The phash is a variable-length `VARCHAR(255)` hex string, so a
      fixed `bit(64)` column would be fragile; instead the search reuses the proven variable-length-safe
      `PerceptualHash.hamming` in Scala (as the existing dedup does). The spec permits a brute-force
      scan and doesn't mandate SQL. No schema change needed.

## 2. Near-duplicate query

- [x] 2.1 (test) Hamming-distance search: within-threshold posts, closest-first, excludes self; empty when none
      (`SimilaritySearchSpec` — pure `SimilaritySearch.rank`; threshold, closest-first, tie-break,
      empty, non-comparable-never-matches even at max threshold.)
- [x] 2.2 (impl) the query (XOR + bit_count / popcount) served from the read model
      (`SimilaritySearch.rank` (Scala Hamming) + `SimilarityService` over `activePhashes`; brute-force,
      fast at personal scale. See 1.1 for why Scala not SQL bit_count.)
- [x] 2.3 (test) reverse-image lookup: hash an arbitrary image → same query finds matches
      (`SimilaritySearchIT` — reverseLookup finds an existing match incl. distance 0; empty for a novel phash.)

## 3. API + UX

- [x] 3.1 (impl) `GET /posts/{id}/similar` and a reverse-lookup endpoint (hash provided/derived)
      (`SimilarityRoutes` — `GET /posts/{id}/similar` + `GET /similar?phash=`; threshold/limit clamped.
      The reverse phash is PROVIDED by the caller — Artemis has no perceptual hasher; Hephaestus/Muses
      computes it.)
- [ ] 3.2 Muses: a "similar" affordance on the post page (Tier 2 slots behind the same UI later)
      (Muses-side UI — out of this repo; served by the endpoints above.)

## Future (Tier 2 — documented, not built)

- [ ] Argus emits a CLIP embedding; Artemis stores in pgvector (HNSW); cosine nearest-neighbor
- [ ] `kind: metadata` reprocess to embed the existing library
