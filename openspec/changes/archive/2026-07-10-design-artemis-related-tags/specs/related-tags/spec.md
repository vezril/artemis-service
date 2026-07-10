# related-tags

An incremental co-occurrence projection in Artemis and a cosine-ranked related-tags query.

## ADDED Requirements

### Requirement: Incremental co-occurrence projection

A projection SHALL maintain, from Post events, a sparse pairwise co-occurrence count
`co(X,Y)` (posts having both tags) and the per-tag post count `n(X)`, updating **incrementally**
— adjusting only the pairs affected by a `TagsChanged` diff, adding a new post's pairs on
`PostCreated`, and decrementing on `PostPurged` — never recomputing from scratch. It SHALL be
rebuildable by replaying the journal.

#### Scenario: A new post updates only its pairs
- **GIVEN** a post created with tags `{cat_ears, cat_girl, 1girl}`
- **WHEN** the projection processes it
- **THEN** `co` for the pairs (cat_ears,cat_girl), (cat_ears,1girl), (cat_girl,1girl) each increase by 1, and `n` for each tag increases by 1

#### Scenario: A tag edit adjusts only the diff
- **GIVEN** a post whose tags change from `{a, b}` to `{a, c}`
- **WHEN** the projection processes the `TagsChanged`
- **THEN** it decrements the (a,b) pair and increments the (a,c) pair (b removed, c added), leaving unaffected pairs untouched

#### Scenario: Edge case — purge decrements the contribution
- **GIVEN** a purged post that had tags `{a, b}`
- **WHEN** the projection processes `PostPurged`
- **THEN** the (a,b) pair count and `n(a)`, `n(b)` are decremented by that post's contribution

### Requirement: Cosine-ranked related tags

Artemis SHALL return, for a given tag X, the top related tags ranked by **cosine similarity**
`co(X,Y) / sqrt(n(X)·n(Y))` computed from the projection, so genuinely-associated tags rank
above ubiquitous ones. Results SHALL be served from the projection (no full-library scan per
query) and exclude X itself.

#### Scenario: Related tags rank by correlation, not raw frequency
- **GIVEN** `cat_ears` co-occurs with `cat_girl` (highly correlated) and with `1girl` (ubiquitous)
- **WHEN** related tags for `cat_ears` are requested
- **THEN** `cat_girl` ranks above `1girl` (cosine down-weights the ubiquitous tag despite its high raw count)

#### Scenario: Edge case — a tag with no co-occurrences returns empty
- **GIVEN** a tag that appears on posts alone (no other tags)
- **WHEN** its related tags are requested
- **THEN** an empty result is returned (not an error)
