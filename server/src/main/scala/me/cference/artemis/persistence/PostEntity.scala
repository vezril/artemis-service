package me.cference.artemis.persistence

import me.cference.artemis.domain.{
  DomainException,
  PostCommand,
  PostDomain,
  PostEvent,
  PostId,
  PostState,
  TagGraph
}
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

/**
 * `PostEntity` wraps the pure post-domain transitions in an `EventSourcedBehavior`
 * (event-persistence spec, OpenSpec task 1.5). `Execute` runs a validated domain command through
 * `PostDomain.decide` and persists any resulting events, replying with a `StatusReply` that mirrors
 * the domain's `Either`.
 *
 * The persistence ID is the frozen contract `post|<id>`.
 */
object PostEntity:

  val EntityPrefix = "post"

  sealed trait Command

  /** Run a validated domain command; persist events and reply success/rejection. */
  final case class Execute(command: PostCommand, replyTo: ActorRef[StatusReply[Done]])
      extends Command

  /** Read-only query of the current folded state (no event persisted). */
  final case class Get(replyTo: ActorRef[PostState]) extends Command

  def persistenceId(post: String): PersistenceId =
    PersistenceId.ofUniqueId(s"$EntityPrefix|$post")

  /**
   * @param tagGraph
   *   supplies the tag relationship graph consulted when canonicalizing `ChangeTags`. Defaults to
   *   `() => TagGraph.empty` so existing call sites keep the no-graph behavior. Production injects
   *   `() => cachedGraph` — a periodically-refreshed snapshot loaded via
   *   [[me.cference.artemis.tags.TagGraphRepository]]. The snapshot is read per command (cheap map
   *   lookup) rather than reloaded from the DB; the cache/refresh loop that keeps it current is a
   *   later milestone (M9) and is intentionally not built here.
   */
  def apply(
      post: PostId,
      tagGraph: () => TagGraph = () => TagGraph.empty
  ): Behavior[Command] =
    EventSourcedBehavior[Command, PostEvent, PostState](
      persistenceId = persistenceId(post.value),
      emptyState = PostState.initial,
      commandHandler = handleCommand(tagGraph),
      eventHandler = PostDomain.evolve
    )

  private def handleCommand(
      tagGraph: () => TagGraph
  )(
      state: PostState,
      command: Command
  ): Effect[PostEvent, PostState] =
    command match
      case Execute(domain, replyTo) =>
        PostDomain.decide(state, domain, tagGraph()) match
          case Right(events) =>
            Effect.persist(events).thenReply(replyTo)(_ => StatusReply.ack())
          case Left(error) =>
            Effect.reply(replyTo)(StatusReply.error(DomainException(error)))

      case Get(replyTo) =>
        Effect.reply(replyTo)(state)
