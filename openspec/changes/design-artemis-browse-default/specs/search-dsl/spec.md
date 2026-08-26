# search-dsl

An empty query is browse-all (newest first); the positive-anchor guardrail applies to non-empty
queries only.

## ADDED Requirements

### Requirement: An empty query is browse-all

A blank or absent `tags` query SHALL be accepted as **browse-all**: every non-deleted post,
default-ordered newest first (`created_at` descending, `id` tiebreak), with the same keyset paging, `order`
override, and response envelope as any other query. Facets over an empty query aggregate the whole
visible catalog. The empty query SHALL NOT pass through the parser (it short-circuits to an empty
plan), so parser guardrails are unaffected.

#### Scenario: Gallery default
- **WHEN** `GET /posts` is called with no `tags` parameter (or a blank one)
- **THEN** it returns the most recent non-deleted posts first, with a keyset `cursor` for the next page

#### Scenario: Order override applies to browse-all
- **WHEN** `GET /posts?order=score` is called with no `tags`
- **THEN** the whole visible catalog is returned ordered by score

## MODIFIED Requirements

### Requirement: A query requires a positive anchor

A NON-EMPTY query consisting solely of negated terms SHALL be rejected (or required to be
combined with a positive term or metatag anchor), because a pure-negation predicate
cannot use the tag index selectively. A blank/absent query is not subject to this rule — it is
browse-all (see "An empty query is browse-all"), not a pure-negation predicate.

#### Scenario: Pure negation rejected
- **Given** the query `-monochrome`
- **When** it is submitted
- **Then** it is rejected with a message indicating at least one positive term is required

#### Scenario: Edge case — metatag anchor satisfies the requirement
- **Given** the query `rating:s -monochrome`
- **When** it is submitted
- **Then** it is accepted (the `rating:s` predicate anchors the query)
