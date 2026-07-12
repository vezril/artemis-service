# Design: Artemis Tag Model & Search DSL

Design record for the Artemis tagging and search subsystem. Captured in explore
mode; no implementation. Terminology and structure follow Danbooru, adapted to
Artemis's Option-C hybrid (event-sourced lifecycle + relational search read model).

## Context & constraints

- **Read model, not entities.** All search runs against a Pekko-Projection-built
  relational read model. The event-sourced Post entities own tag *edits* and
  lifecycle (producing history for free); they are never queried by search.
- **Postgres-native.** `posts.tags text[]` with a GIN index for set intersection;
  `tags.name` with a `pg_trgm` GIN index for autocomplete. No external search engine.
- **Single-user now.** Permission tiers, rate limits, and moderation queues are out
  of scope; the enums/metatags that will later gate on them are stubbed.

## Read-model shape (informative)

```
posts(
  id, tags text[],               -- canonical, alias/implication-resolved
  rating char,                   -- g|s|q|e
  score int, fav_count int,
  width int, height int,
  duration int,                  -- seconds; null for stills
  filetype text,                 -- jpg|png|webm|mp4|gif|...
  filesize bigint, md5 text,
  parent_id, created_at, ...
)  -- GIN(tags), plus btrees for order:/range metatags

tags(name text pk, category smallint, post_count int)  -- GIN(name pg_trgm)
tag_aliases(antecedent -> consequent)
tag_implications(antecedent -> consequent)   -- transitive closure precomputed
```

## Tag model

### Categories

Five categories, matching Danbooru; they namespace search and color the UI.

```
0 general    1 artist    3 copyright    4 character    5 meta
```

(2 is intentionally skipped, preserving Danbooru's numbering so imported data and
muscle memory line up.) Prefixes in search select by category: `artist:foo`,
`character:bar`, `copyright:baz`, `meta:qux`.

### Name normalization

Every tag name is forced to a canonical form on the way in: lowercased, spaces
collapsed to single underscores, trimmed. `Cat Ears` and `cat__ears` both become
`cat_ears`. Names are validated (length, allowed characters). This means tag
identity is unambiguous everywhere — write path, search path, autocomplete.

### Aliases & implications

- **Alias** = same-meaning rename: `catgirl → cat_girl`. The antecedent is rewritten
  to the consequent.
- **Implication** = entailment: `cat_girl ⇒ animal_ears`. Adding the antecedent
  auto-adds the consequent, **transitively** (`cat_girl ⇒ animal_ears ⇒
  animal_humanoid`).

The implication graph changes rarely but is read on every tag edit, so its
transitive closure is precomputed/cached rather than walked per edit.

### Canonicalization pipeline (write path)

Runs when a post's tags are set, **before** the `TagsChanged` event is emitted, so
the journaled/stored tag set is already canonical:

```
raw input ─▶ ① alias rewrite ─▶ ② implication expand (transitive) ─▶ ③ dedup+validate ─▶ canonical set
```

Order matters: aliases resolve first (so implications key off canonical names),
then implications expand, then dedup. `post_count` on `tags` is maintained by the
projection as posts gain/lose tags (and is periodically reconcilable, since
denormalized counts drift).

## Search DSL

### Grammar

```
query      ::= WS? term (WS term)* WS?
term       ::= orTerm | simpleTerm
orTerm     ::= '~' simpleTerm                -- all ~terms join ONE flat OR-set
simpleTerm ::= '-'? atom                     -- '-' negates (tags AND metatags)
atom       ::= metatag | tagPattern
metatag    ::= name ':' value
tagPattern ::= char+ with optional '*'       -- wildcard
value      ::= range | cmp scalar | scalar
range      ::= scalar '..' scalar | scalar '..' | '..' scalar
cmp        ::= '>' | '<' | '>=' | '<='
```

### Metatag catalog

```
scalars/cmp/range : score favcount id mpixels width height ratio filesize
video             : duration fps filetype(webm|mp4|gif) is:video is:animated audio
enums             : rating(g|s|q|e) filetype status(STUB)
relationships     : parent(none|id) child pool ordpool
provenance        : source md5   (user/fav/approver STUBBED until multi-user)
time              : date age
result control    : order limit
```

Comparisons and ranges apply to numeric/date scalars: `score:>10`, `id:100..200`,
`date:2024-01-01..2024-12-31`, `duration:>30`.

### Semantics — the three that carry the weight

1. **Negation** — `-monochrome`, `-rating:e`. Compiles to `NOT (...)`. A query with
   *only* negative terms has no index anchor (`NOT (tags && ...)` scans), so at least
   one positive tag or a metatag anchor is required.
2. **Flat OR (`~`)** — every `~term` joins a single OR-set: `~a ~b c -d` ⇒
   `(a OR b) AND c AND NOT d`. Deliberately non-nested (Danbooru model) so every
   query maps to index-friendly SQL. Parenthesized boolean is a possible future
   extension, explicitly deferred.
3. **Wildcards (`cat_*`)** — expanded at query time against `tags` via trigram, folded
   into an OR of concrete matches, and **capped** (top-N by `post_count`). An
   over-broad wildcard is rejected with "refine your search" rather than run.

### Compilation pipeline

```
raw ─▶ tokenize(quote-aware) ─▶ parse to AST ─▶ ③ alias-resolve (SEARCH-TIME too)
    ─▶ wildcard-expand(capped) ─▶ query plan {includes,excludes,orSet,predicates,order,limit}
    ─▶ SQL against read model
```

```sql
SELECT id, ... FROM posts
WHERE tags @> :includes            -- has all       (GIN)
  AND NOT (tags && :excludes)      -- has none
  AND (tags && :orSet)             -- has at least one   (only if orSet nonempty)
  AND score > 10 AND rating = 's'  -- metatag predicates
ORDER BY score DESC, id DESC
LIMIT :n
```

**Search-time alias resolution** (step ③) is the commonly-missed piece: aliases must
rewrite query terms too, not only saved tags, or aliases feel half-broken.

### Ordering & pagination

```
order:id | score | favcount | duration | mpixels | filesize | random:SEED
```

Reactive infinite scroll uses **keyset (cursor) pagination**, not OFFSET:
`order:id` ⇒ `id < :cursor`; `order:score` ⇒ composite `(score, id)` keyset.
`order:random` is **seeded and stable** — the seed is carried in the cursor so
subsequent pages don't reshuffle (an improvement over Danbooru's jumpy random paging).

### Guardrails

```
max positive tags/query   ~40      wildcard expansion cap   ~100 (top by post_count)
require ≥1 positive anchor          result page ceiling       ~200
```

## Autocomplete (Muses contract)

`GET /tags/autocomplete?q=…&context=tag|metatag` returns
`[{name, category, post_count, alias_of?}]` ranked by `post_count`, backed by the
`pg_trgm` index. It is **grammar-aware**: mid-tag it suggests tags (with category
colors and alias hints); mid-metatag it suggests enum values (`rating:` ⇒ g/s/q/e,
`order:` ⇒ the order options). This single interaction is the biggest driver of a
"modern, not 2000s" feel.

## Deferred / out of scope

- Parenthesized nested boolean search.
- Moderation/permission metatags (`status:`, `user:`, `approver:`) beyond stubs.
- Reverse-image / perceptual-similarity search (belongs to the future `similarity`
  service, not the tag DSL).
- Saved searches, related-tag suggestion, wiki-per-tag.
