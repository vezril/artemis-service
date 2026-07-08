# search-dsl

The Artemis search query language: a Danbooru-style DSL of positional tags, negation,
flat OR groups, wildcards, and metatags, compiled to a single indexed query against
the projection read model (`posts.tags text[]` + GIN). Requirements assume the
[tag-model](../tag-model/spec.md) canonicalization is available for alias resolution.

## ADDED Requirements

### Requirement: Positional tags are AND-combined

Whitespace-separated positional tag terms SHALL be combined with AND: a post matches
only if it carries every positional tag. Compilation SHALL use set-containment against
the indexed tag array.

#### Scenario: Two tags require both
- **Given** the query `1girl cat_ears`
- **When** it is compiled and run
- **Then** only posts whose tag set contains both `1girl` and `cat_ears` match

#### Scenario: Edge case — order and repetition are irrelevant
- **Given** the queries `1girl cat_ears`, `cat_ears 1girl`, and `1girl cat_ears 1girl`
- **When** each is compiled
- **Then** all three produce the same result set

### Requirement: Negation excludes tags and metatags

A term prefixed with `-` SHALL be negated. `-tag` SHALL exclude posts carrying that
tag; `-metatag:value` SHALL exclude posts matching that predicate.

#### Scenario: Tag negation excludes
- **Given** the query `1girl -monochrome`
- **When** it is run
- **Then** matching posts carry `1girl` and do not carry `monochrome`

#### Scenario: Edge case — metatag negation
- **Given** the query `1girl -rating:e`
- **When** it is run
- **Then** matching posts carry `1girl` and have a rating other than `e`

### Requirement: A query requires a positive anchor

A query consisting solely of negated terms SHALL be rejected (or required to be
combined with a positive term or metatag anchor), because a pure-negation predicate
cannot use the tag index selectively.

#### Scenario: Pure negation rejected
- **Given** the query `-monochrome`
- **When** it is submitted
- **Then** it is rejected with a message indicating at least one positive term is required

#### Scenario: Edge case — metatag anchor satisfies the requirement
- **Given** the query `rating:s -monochrome`
- **When** it is submitted
- **Then** it is accepted (the `rating:s` predicate anchors the query)

### Requirement: Flat OR groups via `~`

Terms prefixed with `~` SHALL join a single flat OR-set; a post matches the OR-set if
it carries at least one member. OR SHALL NOT be nestable. The OR-set combines with
other terms by AND.

#### Scenario: OR of two tags, ANDed with a third
- **Given** the query `~cat_ears ~dog_ears animal`
- **When** it is compiled
- **Then** it means `(cat_ears OR dog_ears) AND animal`

#### Scenario: Edge case — single `~` term degrades to a plain include
- **Given** the query `~cat_ears 1girl`
- **When** it is compiled
- **Then** it is equivalent to `cat_ears 1girl` (a one-member OR-set)

### Requirement: Wildcard tag expansion is bounded

A tag pattern containing `*` SHALL be expanded at query time to matching tag names
(via the trigram index), combined as an OR of the concrete matches. The expansion
SHALL be capped (top-N by `post_count`); a pattern matching more than the cap SHALL be
rejected with guidance to refine, never run unbounded.

#### Scenario: Wildcard matches a bounded set
- **Given** tags `cat_ears`, `cat_girl`, `cat_tail` and the query `cat_*`
- **When** it is expanded
- **Then** the query matches posts carrying any of `cat_ears`, `cat_girl`, or `cat_tail`

#### Scenario: Edge case — over-broad wildcard rejected
- **Given** a pattern such as `a*` matching more tags than the expansion cap
- **When** it is expanded
- **Then** the query is rejected with a "refine your search" message rather than executed

### Requirement: Metatags for scalars, ranges, enums, and video

Metatags of the form `name:value` SHALL support comparison (`>` `<` `>=` `<=`), range
(`a..b`, `a..`, `..b`), enum, and exact forms as appropriate to the field. The catalog
SHALL include at minimum: `rating` (`g|s|q|e`), `score`, `favcount`, `id`, `width`,
`height`, `mpixels`, `ratio`, `filesize`, `filetype`, `parent`, `pool`, `date`, `age`,
`source`, `md5`, and the video fields `duration`, `fps`, `audio`, and `is:video` /
`is:animated`.

#### Scenario: Comparison metatag
- **Given** the query `score:>10`
- **When** it is compiled
- **Then** it restricts results to posts with `score` greater than 10

#### Scenario: Video duration range
- **Given** the query `is:video duration:30..120`
- **When** it is compiled
- **Then** it restricts to video posts whose duration is between 30 and 120 seconds inclusive

#### Scenario: Edge case — four-tier rating enum
- **Given** the queries `rating:g` and `rating:e`
- **When** each is compiled
- **Then** `rating:g` selects only `general` posts and `rating:e` selects only `explicit` posts

### Requirement: Search-time alias resolution

Tag terms in a query SHALL be alias-resolved using the same tag-model rules applied on
the write path, so that searching an antecedent finds posts stored under its
consequent.

#### Scenario: Searching an alias finds canonical posts
- **Given** an alias `catgirl -> cat_girl` and posts stored with `cat_girl`
- **When** the query `catgirl` is run
- **Then** those posts match

#### Scenario: Edge case — negated alias resolves too
- **Given** an alias `catgirl -> cat_girl`
- **When** the query `1girl -catgirl` is run
- **Then** posts carrying `cat_girl` are excluded

### Requirement: Ordering with keyset pagination and stable seeded random

The `order:` metatag SHALL select result ordering from at least `id`, `score`,
`favcount`, `duration`, `mpixels`, `filesize`, and `random`. Pagination SHALL use
keyset (cursor) semantics rather than OFFSET. `order:random` SHALL be seeded such that
the seed is carried across pages, producing a stable shuffle.

#### Scenario: Default and explicit ordering
- **Given** the query `1girl order:score`
- **When** it is run
- **Then** results are ordered by descending score with a deterministic tiebreak on `id`

#### Scenario: Edge case — stable random across pages
- **Given** the query `order:random` and its first page returned with a seed in the cursor
- **When** the next page is requested using that cursor
- **Then** the ordering is consistent with the first page (no reshuffle) and no post is repeated or skipped

### Requirement: Query guardrails

The DSL SHALL enforce bounds: a maximum number of positive tags per query, a wildcard
expansion cap, and a maximum result page size. Exceeding a bound SHALL produce a clear
rejection rather than a degraded full-table scan.

#### Scenario: Too many tags rejected
- **Given** a query with more positive tags than the configured maximum
- **When** it is submitted
- **Then** it is rejected with a message naming the limit

#### Scenario: Edge case — page size clamped
- **Given** a request for more results than the page ceiling (e.g. `limit:100000`)
- **When** it is compiled
- **Then** the effective page size is clamped to the ceiling
