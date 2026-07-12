# similarity-search Specification

## Purpose
TBD - created by archiving change design-artemis-find-similar. Update Purpose after archive.
## Requirements
### Requirement: Near-duplicate search by perceptual-hash Hamming distance

Artemis SHALL find posts visually near-duplicate to a given post by comparing perceptual
hashes via Hamming distance, returning posts within a configurable threshold ordered by
increasing distance (closest first), served from the read model and excluding the source
post. At personal scale a brute-force scan is acceptable.

#### Scenario: Similar posts are returned closest-first
- **GIVEN** a post A and other posts whose phashes differ from A's by 2, 6, and 40 bits
- **WHEN** a near-duplicate search for A runs with threshold 10
- **THEN** it returns the 2-bit and 6-bit posts (closest first) and excludes the 40-bit post and A itself

#### Scenario: Edge case — no near matches returns empty
- **GIVEN** a post whose phash is far (beyond threshold) from every other post
- **WHEN** the search runs
- **THEN** it returns no results (not an error)

### Requirement: Reverse-image lookup from an arbitrary image

Artemis SHALL support reverse-image lookup: given an arbitrary image (not yet a post), its
perceptual hash is computed and the same Hamming-distance search finds near-matching posts —
so a user can ask "do I already have this?" and the upload dedup warning is the same
mechanism.

#### Scenario: A dropped image finds its existing match
- **GIVEN** an image whose phash is within threshold of an existing post
- **WHEN** a reverse-image lookup runs
- **THEN** it returns that existing post as a near match

#### Scenario: Edge case — a novel image finds nothing
- **GIVEN** an image with no near match in the library
- **WHEN** the lookup runs
- **THEN** it returns no matches (the image is new to the library)

