# tag-model Specification

## Purpose
TBD - created by archiving change design-artemis-tag-search. Update Purpose after archive.
## Requirements
### Requirement: Canonical tag-name normalization

A tag name SHALL be normalized to a single canonical form before storage or
comparison: lowercased, leading/trailing whitespace trimmed, internal whitespace and
runs of underscores collapsed to a single underscore. Names failing validation
(empty after normalization, or exceeding the maximum length) SHALL be rejected with
a typed error, never stored.

#### Scenario: Mixed case and spacing normalized
- **Given** the raw tag input `"Cat Ears"`
- **When** the name is normalized
- **Then** the canonical form is `cat_ears`

#### Scenario: Edge case — collapsed separators
- **Given** the raw inputs `"cat__ears"`, `"  cat ears  "`, and `"cat_ears"`
- **When** each is normalized
- **Then** all three yield the identical canonical form `cat_ears`

#### Scenario: Edge case — empty and oversized rejected
- **Given** an input that is only whitespace, and an input exceeding the maximum name length
- **When** normalization is attempted on each
- **Then** each result is a typed rejection and nothing is stored

### Requirement: Five tag categories with search namespaces

Every tag SHALL carry exactly one category from the fixed set `general` (0),
`artist` (1), `copyright` (3), `character` (4), `meta` (5). The numbering SHALL be
preserved (2 intentionally unused) for Danbooru data compatibility. A category prefix
in a search term SHALL restrict matching to tags of that category.

#### Scenario: Category prefix restricts matching
- **Given** tags `character:cheshire_cat` and a general tag `cat`
- **When** a search term `character:cheshire_cat` is evaluated
- **Then** only the character-category tag matches, not the general tag

#### Scenario: Edge case — invalid category rejected
- **Given** an attempt to assign a tag a category value outside the fixed set (e.g. `2` or `9`)
- **When** the category is validated
- **Then** the assignment is rejected with a typed error

### Requirement: Tag aliases rewrite antecedent to consequent

A tag alias SHALL map an antecedent name to a consequent name. Wherever a tag name is
canonicalized (write path and search path alike), an antecedent SHALL be rewritten to
its consequent. Alias chains SHALL resolve to a terminal consequent.

#### Scenario: Antecedent rewritten on tagging
- **Given** an alias `catgirl -> cat_girl`
- **When** a post is tagged with `catgirl`
- **Then** the stored canonical tag set contains `cat_girl` and not `catgirl`

#### Scenario: Edge case — alias chain resolves to terminal
- **Given** aliases `a -> b` and `b -> c`
- **When** `a` is canonicalized
- **Then** the result is `c`

### Requirement: Transitive tag implications expand the tag set

A tag implication SHALL map an antecedent to a consequent such that adding the
antecedent to a post also adds the consequent. Implications SHALL apply transitively:
the full reachable set of consequents is added.

#### Scenario: Transitive expansion
- **Given** implications `cat_girl => animal_ears` and `animal_ears => animal_humanoid`
- **When** a post is tagged with `cat_girl`
- **Then** the canonical tag set contains `cat_girl`, `animal_ears`, and `animal_humanoid`

#### Scenario: Edge case — implication cycle terminates
- **Given** implications forming a cycle `x => y` and `y => x`
- **When** a post is tagged with `x`
- **Then** expansion terminates and the tag set contains exactly `x` and `y` (no infinite loop, no duplicates)

### Requirement: Write-path canonicalization pipeline ordering

When a post's tags are set, the tag set SHALL be canonicalized in the order (1) alias
rewrite, (2) transitive implication expansion, (3) dedup and validate — and only the
resulting canonical set SHALL be emitted in the `TagsChanged` event. Aliases resolve
before implications so implication rules key off canonical names.

#### Scenario: Alias resolves before implication
- **Given** an alias `catgirl -> cat_girl` and an implication `cat_girl => animal_ears`
- **When** a post is tagged with `catgirl`
- **Then** the canonical set contains `cat_girl` and `animal_ears`

#### Scenario: Edge case — duplicates from alias+implication collapse
- **Given** a post tagged with both `catgirl` and `cat_girl` where `catgirl -> cat_girl`
- **When** the pipeline runs
- **Then** `cat_girl` appears exactly once in the canonical set

### Requirement: Projection-maintained tag post counts

Each tag's `post_count` SHALL be maintained by the read-model projection as posts gain
and lose the tag, and SHALL be reconcilable (recomputable from the read model) since a
denormalized count can drift.

#### Scenario: Count follows tag membership
- **Given** a tag `cat_ears` with `post_count` 0
- **When** two posts are tagged `cat_ears` and later one is untagged
- **Then** the projected `post_count` is 1

#### Scenario: Edge case — reconciliation corrects drift
- **Given** a `post_count` that has drifted from the true membership
- **When** reconciliation runs
- **Then** the count is reset to the exact number of posts carrying the tag

