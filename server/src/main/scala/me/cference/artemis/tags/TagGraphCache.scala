package me.cference.artemis.tags

import me.cference.artemis.domain.TagGraph
import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

/**
 * An in-memory, periodically-refreshed snapshot of the tag relationship graph (service-runtime
 * spec). The graph changes rarely but is consulted on every tag edit (write-path canonicalization)
 * and every search (alias resolution), so both read the cached snapshot via [[current]] rather than
 * loading from Postgres per operation.
 *
 * Staleness is bounded by the refresh interval and is acceptable at personal scale: a write
 * canonicalizes against a recent graph, a search resolves aliases against it, and both tolerate
 * sub-minute lag. [[refresh]] atomically swaps the snapshot; Main calls it once at startup (before
 * binding HTTP) and then schedules it via [[startScheduledRefresh]].
 *
 * @param load
 *   the one-shot graph loader; production passes `() => tagGraphRepo.loadGraph()`.
 */
final class TagGraphCache(load: () => Future[TagGraph])(using ec: ExecutionContext):

  private val ref: AtomicReference[TagGraph] = new AtomicReference(TagGraph.empty)

  /** The current snapshot (empty until the first [[refresh]] completes). Cheap — a field read. */
  def current: TagGraph = ref.get()

  /** A supplier over [[current]], for injecting into the entity/search seams. */
  def snapshot: () => TagGraph = () => ref.get()

  /** Reload the graph and atomically swap the snapshot; yields the freshly-loaded graph. */
  def refresh(): Future[TagGraph] =
    load().map { graph =>
      ref.set(graph)
      graph
    }

  /**
   * Schedule [[refresh]] every `interval` (first refresh one interval out — the caller loads once
   * synchronously at startup before this). A refresh failure is swallowed so the ticker keeps
   * running and the last-good snapshot stands. Returns a handle that cancels the schedule; the
   * stream is also torn down by CoordinatedShutdown on system termination.
   */
  def startScheduledRefresh(
      interval: FiniteDuration
  )(using system: ActorSystem[?]): Cancellable =
    Source
      .tick(interval, interval, ())
      .mapAsync(1)(_ => refresh().map(_ => ()).recover { case _ => () })
      .toMat(Sink.ignore)(Keep.left)
      .run()
