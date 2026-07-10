package me.cference.artemis.persistence

import me.cference.artemis.domain.{
  DomainException,
  SavedSearchesCommand,
  SavedSearchesDomain,
  SavedSearchesEvent,
  SavedSearchesState
}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

/**
 * `SavedSearchesEntity` wraps the pure saved-searches transitions in an `EventSourcedBehavior`
 * (saved-searches spec, task 1.2). Single-user = one list, so there is a single instance addressed
 * by the fixed id [[SingletonId]] (persistence id `saved-searches|default`). `Execute` runs a
 * command through `SavedSearchesDomain.decide` and persists any events; `Get` returns the folded
 * list for read-your-writes reads.
 */
object SavedSearchesEntity:

  val EntityPrefix = "saved-searches"

  /** The one list's id (multi-user later would key per user — additive). */
  val SingletonId = "default"

  sealed trait Command

  /** Run a validated command; persist events and reply success/rejection. */
  final case class Execute(command: SavedSearchesCommand, replyTo: ActorRef[StatusReply[Done]])
      extends Command

  /** Read-only query of the current folded list (no event persisted). */
  final case class Get(replyTo: ActorRef[SavedSearchesState]) extends Command

  def persistenceId(id: String): PersistenceId =
    PersistenceId.ofUniqueId(s"$EntityPrefix|$id")

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, SavedSearchesEvent, SavedSearchesState](
      persistenceId = persistenceId(id),
      emptyState = SavedSearchesState.initial,
      commandHandler = handleCommand,
      eventHandler = SavedSearchesDomain.evolve
    )

  private def handleCommand(
      state: SavedSearchesState,
      command: Command
  ): Effect[SavedSearchesEvent, SavedSearchesState] =
    command match
      case Execute(domain, replyTo) =>
        SavedSearchesDomain.decide(state, domain) match
          case Right(events) =>
            Effect.persist(events).thenReply(replyTo)(_ => StatusReply.ack())
          case Left(error) =>
            Effect.reply(replyTo)(StatusReply.error(DomainException(error)))

      case Get(replyTo) =>
        Effect.reply(replyTo)(state)
