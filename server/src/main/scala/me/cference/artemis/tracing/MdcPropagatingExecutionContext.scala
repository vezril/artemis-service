package me.cference.artemis.tracing

import org.slf4j.MDC

import scala.concurrent.ExecutionContext

/**
 * An [[ExecutionContext]] that carries the SLF4J MDC of the thread that REGISTERED a `Future`
 * continuation onto the worker thread that later runs it (request-tracing). MDC is a thread-local,
 * so a `correlationId` set at request/message entry would otherwise be invisible to a
 * `logger.trace` running in a `Future` continuation, a projection callback, or after an actor ask.
 *
 * The capture point matters. A `Future` combinator (`map`/`flatMap`/…) invokes `ExecutionContext`'s
 * `execute` at DISPATCH time — on whatever thread completes the antecedent, which for a Pekko ask,
 * a stream materialization, or any hop rooted in an untraced EC is a foreign thread with an empty
 * MDC. Capturing there would silently lose the id. Instead we snapshot in [[prepare]], which the
 * standard library calls SYNCHRONOUSLY on the thread that registers the continuation (verified on
 * this Scala version), so the id set on the caller's thread rides through even when the antecedent
 * is completed elsewhere. The snapshot is installed while the task runs and the worker thread's
 * prior MDC is restored afterward — symmetric set/restore so nothing leaks between tasks on a
 * reused pool thread.
 *
 * A direct `execute` call that bypasses `prepare` (rare — e.g. `blocking`/runnable submission)
 * still gets a best-effort dispatch-time snapshot, so it is never worse than the caller's current
 * MDC.
 */
final class MdcPropagatingExecutionContext(delegate: ExecutionContext) extends ExecutionContext:

  /**
   * Snapshot the registering thread's MDC and hand back an EC that installs it around each task.
   */
  override def prepare(): ExecutionContext =
    val captured = Option(MDC.getCopyOfContextMap)
    new ExecutionContext:
      def execute(runnable: Runnable): Unit = runWith(captured, runnable)
      def reportFailure(cause: Throwable): Unit = delegate.reportFailure(cause)

  /** Fallback for callers that skip `prepare`: capture at dispatch time (the caller's MDC). */
  def execute(runnable: Runnable): Unit = runWith(Option(MDC.getCopyOfContextMap), runnable)

  def reportFailure(cause: Throwable): Unit = delegate.reportFailure(cause)

  /** Run `runnable` on the delegate with `captured` installed, restoring the prior MDC after. */
  private def runWith(captured: Option[java.util.Map[String, String]], runnable: Runnable): Unit =
    delegate.execute { () =>
      val previous = Option(MDC.getCopyOfContextMap)
      install(captured)
      try runnable.run()
      finally install(previous)
    }

  /** Replace the current thread's MDC with `map` (or clear it when there was none). */
  private def install(map: Option[java.util.Map[String, String]]): Unit =
    map.fold(MDC.clear())(MDC.setContextMap)
