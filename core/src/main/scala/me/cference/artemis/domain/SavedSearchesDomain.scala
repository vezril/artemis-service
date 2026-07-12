package me.cference.artemis.domain

import me.cference.artemis.domain.SavedSearchesCommand.*
import me.cference.artemis.domain.SavedSearchesEvent.*

/**
 * Pure state-transition logic for the saved-searches aggregate. Two total functions:
 *
 *   - [[decide]] : `(State, Command) => Either[DomainError, Seq[Event]]` — validates a command,
 *     producing events (or an error and zero events). No side effects, no clock reads.
 *   - [[evolve]] : `(State, Event) => State` — folds a fact into state.
 *
 * Invariants: names are unique; saving is an upsert (identical query is a no-op); renaming requires
 * the source to exist and the target to be free; removing an absent name is an idempotent no-op.
 */
object SavedSearchesDomain:

  def decide(
      state: SavedSearchesState,
      command: SavedSearchesCommand
  ): Either[DomainError, Seq[SavedSearchesEvent]] =
    command match
      case SaveSearch(name, query, at) =>
        // Upsert: re-saving the same name updates its query; an identical save emits nothing.
        state.get(name) match
          case Some(existing) if existing.query == query => Right(Seq.empty)
          case _ => Right(Seq(SearchSaved(name, query, at)))

      case RenameSearch(from, to, at) =>
        if from == to then Right(Seq.empty)
        else if !state.has(from) then Left(DomainError.SavedSearchNotFound)
        else if state.has(to) then Left(DomainError.SavedSearchNameConflict)
        else Right(Seq(SearchRenamed(from, to, at)))

      case RemoveSearch(name, at) =>
        // Idempotent: removing an absent name is an accepted no-op.
        if state.has(name) then Right(Seq(SearchRemoved(name, at))) else Right(Seq.empty)

  def evolve(state: SavedSearchesState, event: SavedSearchesEvent): SavedSearchesState =
    event match
      case SearchSaved(name, query, _) =>
        if state.has(name) then
          state.copy(searches =
            state.searches.map(s => if s.name == name then SavedSearch(name, query) else s)
          )
        else state.copy(searches = state.searches :+ SavedSearch(name, query))

      case SearchRenamed(from, to, _) =>
        state.copy(searches =
          state.searches.map(s => if s.name == from then SavedSearch(to, s.query) else s)
        )

      case SearchRemoved(name, _) =>
        state.copy(searches = state.searches.filterNot(_.name == name))

  /** Convenience for tests/replay: fold a full event list from the initial state. */
  def replay(events: Seq[SavedSearchesEvent]): SavedSearchesState =
    events.foldLeft(SavedSearchesState.initial)(evolve)
