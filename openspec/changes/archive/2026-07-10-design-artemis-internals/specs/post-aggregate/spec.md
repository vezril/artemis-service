# post-aggregate

The event-sourced `Post` entity — the write-side source of truth for a post's content,
tags, lifecycle, rating, relationships, favorites, and scores. Pure `decide`/`evolve`,
journaled via Pekko Persistence, in the Apollo/Hermes house style.

## ADDED Requirements

### Requirement: Post lifecycle as an event-sourced entity

Each post SHALL be a persistent `EventSourcedBehavior` entity (id `post|<n>`) whose state
is folded from journaled events, with lifecycle `pending → active → deleted`. `CreatePost`
SHALL emit `PostCreated` (status pending); `RecordProcessed` SHALL emit `MediaProcessed`
and move an active-eligible post to active; `Delete`/`Restore` SHALL toggle deletion.
Commands on a deleted post SHALL be rejected.

#### Scenario: A created post starts pending and becomes active on processing
- **GIVEN** a new post
- **WHEN** `CreatePost` then `RecordProcessed(...)` are handled
- **THEN** it emits `PostCreated` (pending) then `MediaProcessed`, and its status becomes active

#### Scenario: Edge case — a command on a deleted post is rejected
- **GIVEN** a post folded from `[PostCreated, Deleted]`
- **WHEN** `ChangeTags` is handled
- **THEN** it returns a typed rejection (post not found/deleted) and emits no event

### Requirement: Canonical tag edits with history

`ChangeTags` SHALL canonicalize the requested tag set (alias rewrite → transitive
implication expansion → dedup, per the tag model) **before** emitting `TagsChanged`, so the
journal always holds the canonical set. The sequence of `TagsChanged` events SHALL
constitute the post's tag-edit history.

#### Scenario: Tags are canonicalized before the event
- **GIVEN** an alias `catgirl → cat_girl` and an implication `cat_girl ⇒ animal_ears`
- **WHEN** `ChangeTags(["catgirl"])` is handled
- **THEN** the emitted `TagsChanged` carries the canonical set `{cat_girl, animal_ears}`

#### Scenario: Edge case — history is reconstructable from the journal
- **GIVEN** a post with several `TagsChanged` events
- **WHEN** the journal is replayed
- **THEN** each prior tag set is recoverable (the edit history), with no external lookup

### Requirement: Rating, relationships, favorites, and scores as events

The `Post` entity SHALL model rating (`SetRating`, ∈ {g,s,q,e}), parent/child
(`SetParent`), favorites (`Favorite`/`Unfavorite`), and score (`Score`) as commands
producing events (`RatingChanged`, `ParentSet`, `Favorited`/`Unfavorited`, `Scored`) —
uniformly event-sourced. Favoriting an already-favorited post SHALL be idempotent.

#### Scenario: Favoriting emits an event and is idempotent
- **GIVEN** a post not currently favorited
- **WHEN** `Favorite` is handled, then handled again
- **THEN** the first emits `Favorited`; the second is an accepted no-op (no duplicate event)

#### Scenario: Edge case — an invalid rating is rejected
- **GIVEN** a `SetRating` with a value outside {g,s,q,e}
- **WHEN** it is handled
- **THEN** it returns a typed rejection and emits no event
