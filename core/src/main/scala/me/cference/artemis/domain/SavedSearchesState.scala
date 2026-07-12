package me.cference.artemis.domain

/** One saved search: a human name bound to a raw DSL query string. */
final case class SavedSearch(name: SearchName, query: String)

/**
 * The folded state of the saved-searches aggregate: the ordered list of saved searches (insertion
 * order, so the UI list is stable). Names are unique within the list — the domain enforces it.
 */
final case class SavedSearchesState(searches: Vector[SavedSearch]):
  def get(name: SearchName): Option[SavedSearch] = searches.find(_.name == name)
  def has(name: SearchName): Boolean = searches.exists(_.name == name)

object SavedSearchesState:
  val initial: SavedSearchesState = SavedSearchesState(Vector.empty)
