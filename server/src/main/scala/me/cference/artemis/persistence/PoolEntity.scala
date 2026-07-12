package me.cference.artemis.persistence

import me.cference.artemis.domain.{
  DomainException,
  PoolCommand,
  PoolDomain,
  PoolEvent,
  PoolId,
  PoolState
}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

/**
 * `PoolEntity` wraps the pure pool-domain transitions in an `EventSourcedBehavior`
 * (event-persistence spec, OpenSpec task 1.5). `Execute` runs a validated domain command through
 * `PoolDomain.decide` and persists any resulting events, replying with a `StatusReply` that mirrors
 * the domain's `Either`.
 *
 * The persistence ID is the frozen contract `pool|<id>`.
 */
object PoolEntity:

  val EntityPrefix = "pool"

  sealed trait Command

  /** Run a validated domain command; persist events and reply success/rejection. */
  final case class Execute(command: PoolCommand, replyTo: ActorRef[StatusReply[Done]])
      extends Command

  /** Read-only query of the current folded state (no event persisted). */
  final case class Get(replyTo: ActorRef[PoolState]) extends Command

  def persistenceId(pool: String): PersistenceId =
    PersistenceId.ofUniqueId(s"$EntityPrefix|$pool")

  def apply(pool: PoolId): Behavior[Command] =
    EventSourcedBehavior[Command, PoolEvent, PoolState](
      persistenceId = persistenceId(pool.value),
      emptyState = PoolState.initial,
      commandHandler = handleCommand,
      eventHandler = PoolDomain.evolve
    )

  private def handleCommand(
      state: PoolState,
      command: Command
  ): Effect[PoolEvent, PoolState] =
    command match
      case Execute(domain, replyTo) =>
        PoolDomain.decide(state, domain) match
          case Right(events) =>
            Effect.persist(events).thenReply(replyTo)(_ => StatusReply.ack())
          case Left(error) =>
            Effect.reply(replyTo)(StatusReply.error(DomainException(error)))

      case Get(replyTo) =>
        Effect.reply(replyTo)(state)
