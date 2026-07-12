package me.cference.artemis.domain

import java.time.Instant

/**
 * Facts that have happened to the saved-searches aggregate. Each event is self-contained: folding
 * state purely from an event list needs no external lookup. These are the on-disk journal contract
 * — evolve additively only, never rename the type tags.
 */
sealed trait SavedSearchesEvent extends CborSerializable:
  def at: Instant

object SavedSearchesEvent:
  final case class SearchSaved(name: SearchName, query: String, at: Instant)
      extends SavedSearchesEvent
  final case class SearchRenamed(from: SearchName, to: SearchName, at: Instant)
      extends SavedSearchesEvent
  final case class SearchRemoved(name: SearchName, at: Instant) extends SavedSearchesEvent
