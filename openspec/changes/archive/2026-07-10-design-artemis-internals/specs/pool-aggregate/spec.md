# pool-aggregate

The event-sourced `Pool` entity — an ordered, named collection of posts with its own
history, independent of the post journals.

## ADDED Requirements

### Requirement: Ordered pool membership as an event-sourced entity

Each pool SHALL be a persistent entity (id `pool|<n>`) supporting `CreatePool`, `AddPost`,
`RemovePost`, `Reorder`, `RenamePool`, and `DeletePool`, emitting corresponding events, and
`evolve` SHALL maintain an ordered post sequence. Adding a post already in the pool SHALL be
idempotent; removing an absent post SHALL be rejected.

#### Scenario: Posts are added and kept in order
- **GIVEN** a pool with posts `[a, b]`
- **WHEN** `AddPost(c)` is handled
- **THEN** it emits `PostAdded(c)` and the ordered membership becomes `[a, b, c]`

#### Scenario: Edge case — reordering persists the new sequence
- **GIVEN** a pool `[a, b, c]`
- **WHEN** `Reorder([c, a, b])` is handled
- **THEN** it emits `Reordered` and the membership folds to `[c, a, b]`

#### Scenario: Edge case — adding a duplicate is an idempotent no-op
- **GIVEN** a pool already containing `a`
- **WHEN** `AddPost(a)` is handled
- **THEN** it is an accepted no-op (no duplicate event, order unchanged)
