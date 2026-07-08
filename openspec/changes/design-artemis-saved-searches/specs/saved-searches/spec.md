# saved-searches

Store, manage, and run named DSL queries. (Subscriptions/feeds via watermarks are a documented
future extension, not specified here.)

## ADDED Requirements

### Requirement: Manage named saved searches

Artemis SHALL let a named DSL query be saved, listed, renamed, and removed, stored as a small
event-sourced aggregate. A name SHALL be unique within the list; saving a name that exists SHALL
update its query (or be rejected as a duplicate — implementer's choice, but deterministic).

#### Scenario: A query is saved and listed
- **GIVEN** the query `cat_ears is:video`
- **WHEN** it is saved as "cat videos"
- **THEN** "cat videos" appears in the saved-search list with that query

#### Scenario: Edge case — removing a saved search
- **GIVEN** a saved search "cat videos"
- **WHEN** it is removed
- **THEN** it no longer appears in the list, and the underlying posts are unaffected

### Requirement: Run a saved search

Running a saved search SHALL resolve to its stored query and execute the normal DSL search (same
results as typing the query), so a saved search is a shortcut, not a separate result set.

#### Scenario: Running a saved search matches typing its query
- **GIVEN** a saved search "cat videos" = `cat_ears is:video`
- **WHEN** it is run
- **THEN** it returns the same results as entering `cat_ears is:video` directly

#### Scenario: Edge case — a saved query using a since-removed tag still runs
- **GIVEN** a saved query referencing a tag that no longer exists on any post
- **WHEN** it is run
- **THEN** it executes normally and returns an empty (or reduced) result set (not an error)
