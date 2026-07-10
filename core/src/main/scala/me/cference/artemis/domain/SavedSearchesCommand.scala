package me.cference.artemis.domain

import java.time.Instant

/**
 * Commands directed at the saved-searches aggregate (single-user: one list). `at` is the
 * caller-supplied instant; the pure decider stamps produced events with it, keeping transitions
 * deterministic (no hidden clock reads).
 */
sealed trait SavedSearchesCommand:
  def at: Instant

object SavedSearchesCommand:
  /**
   * Save (or upsert) a named DSL query. Re-saving a name updates its query; identical is a no-op.
   */
  final case class SaveSearch(name: SearchName, query: String, at: Instant)
      extends SavedSearchesCommand

  /** Rename a saved search. `from` must exist and `to` must not already be taken. */
  final case class RenameSearch(from: SearchName, to: SearchName, at: Instant)
      extends SavedSearchesCommand

  /** Remove a saved search. Removing an absent name is an accepted no-op (idempotent DELETE). */
  final case class RemoveSearch(name: SearchName, at: Instant) extends SavedSearchesCommand
