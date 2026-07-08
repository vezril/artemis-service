# Design: find-similar

Reverse-image / "find similar." Captured in explore mode; no implementation.

## Tier 1 — near-duplicate via perceptual hash (the active design)

Every post has a phash (from Hephaestus). Similarity = **Hamming distance** (bits differing).
"Find similar to A" = posts whose phash is within N bits of A's:

```
   SELECT id FROM posts
   WHERE id <> :self
   ORDER BY bit_count(phash # :target)      -- '#' = XOR, bit_count = popcount = Hamming
   LIMIT :k
   -- (optionally WHERE bit_count(phash # :target) <= :threshold)
```

A brute-force scan of XOR+popcount over even ~100k 64-bit hashes is milliseconds — **fast
enough at personal scale with no index and no separate service.** It runs in Artemis over
the phash already in the read model.

What Tier 1 covers:
```
   ✓ "find near-duplicates / variants / edits of this post"
   ✓ reverse-image lookup — hash a dropped image, run the same query ("do I already have this?")
   ✓ generalizes the upload dedup warning (same mechanism)
```

Its limit: phash catches "**same image, slightly different**" (resize, recompress, minor
edit, recolor) — NOT "different image that looks alike." That's Tier 2.

## Tier 2 — semantic "visually similar" (FUTURE ENHANCEMENT, documented only)

For true "these *look* alike" discovery (same subject/style/composition across different
images), use **embeddings** + vector search. The elegant fit: **Argus already runs vision
models**, so it can also emit a CLIP embedding per image at ~no extra cost; Artemis stores it
in **`pgvector`** (a Postgres extension — stays Postgres-native, no FAISS server) and does
cosine nearest-neighbor search.

```
   Argus  ── tag suggestions (have) ── + CLIP embedding [~512-dim] (add later)
                                              │
                          Artemis: pgvector column + HNSW index
                                              │
                    "visually similar to X" = cosine nearest-neighbor query
```

When built, Tier 2:
- reuses Argus (the vision service already loading the image),
- stays in Postgres (`pgvector`, no new service),
- needs a `kind: metadata` **reprocess** (`design-artemis-reprocessing`) to embed the
  existing library,
- and slots behind the same "similar" UI as Tier 1 (near-dups + visually-similar).

### Tier 2 also powers RELATIONSHIP SUGGESTIONS (future)

Beyond "show me similar," Tier 2 (with Tier 1) becomes a **relationship suggester** — it
proposes links the human confirms (same philosophy as auto-tag review). Three flavors of
"related," each mapping to a mechanism:

```
   variants of ONE image (alt res/edit/take)  → PARENT/CHILD (exists) · Tier 1 phash suggests
   same CHARACTER across images                → the character TAG · Tier 2 semantics suggests
   same SCENE / a flat "these go together" set → a new "ALTERNATES" group  ← new concept
```

**Alternates** = a lightweight **flat, unordered group** linking posts that depict the same
character/scene/subject — distinct from parent/child (hierarchical image variants) and pools
(ordered sequences). Tier 2 similarity suggests candidate members ("these look like alternates
of this post — group them?"); the user confirms. Open sub-question for when it's built: a
dedicated alternates relationship vs. reusing parent/child (Danbooru folds alternates into
parent/child).

Deferred deliberately — Tier 1 delivers the near-dup value now for nearly free; the
relationship-suggestion + alternates layer rides on Tier 2 embeddings later.

## UX (Tier 1 now; Tier 2 later behind the same affordance)

```
   post page:  "similar" → near-duplicates (Tier 1)  [+ visually-similar (Tier 2, later)]
   upload:     dedup warning (Tier 1, already designed)
   reverse:    drag an image in → hash → find matches (Tier 1)
```

## Out of scope

Tier 2 (embeddings, pgvector, semantic search) — documented above as a future enhancement,
not implemented by this change.
