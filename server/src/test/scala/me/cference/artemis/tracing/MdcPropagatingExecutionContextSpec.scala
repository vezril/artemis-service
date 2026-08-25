package me.cference.artemis.tracing

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.MDC

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
 * The MDC-propagating EC (request-tracing capability): a value set on the submitting thread must be
 * visible on the worker thread and survive across chained `Future` hops, and it must NOT leak onto
 * a pool thread once the task completes. Mirrors Apollo's spec (shared primitive, same guarantees).
 */
final class MdcPropagatingExecutionContextSpec extends AnyWordSpec with Matchers with ScalaFutures:

  // A single-thread delegate so "does the value leak on the pool thread afterwards?" is observable
  // on the very same thread the task ran on.
  private def singleThreadEc(): (ExecutionContext, () => Unit) =
    val pool = Executors.newSingleThreadExecutor()
    (ExecutionContext.fromExecutor(pool), () => { val _ = pool.shutdownNow() })

  "MdcPropagatingExecutionContext" should {

    "carry the submit-time MDC onto the worker thread" in {
      val (delegate, close) = singleThreadEc()
      try
        val ec = MdcPropagatingExecutionContext(delegate)
        MDC.put("correlationId", "abc123")
        try
          val seen = Future(Option(MDC.get("correlationId")))(ec)
          seen.futureValue shouldBe Some("abc123")
        finally MDC.remove("correlationId")
      finally close()
    }

    "keep the value across chained Future hops" in {
      val (delegate, close) = singleThreadEc()
      try
        given ec: ExecutionContext = MdcPropagatingExecutionContext(delegate)
        MDC.put("correlationId", "deep")
        try
          val seen = Future(())
            .map(_ => ())
            .flatMap(_ => Future(()))
            .map(_ => Option(MDC.get("correlationId")))
          seen.futureValue shouldBe Some("deep") // survived three hops
        finally MDC.remove("correlationId")
      finally close()
    }

    "survive a hop whose antecedent Future is completed by a foreign (no-MDC) thread" in {
      // The realistic shape: a Pekko ask / stream materialization completes the antecedent on an
      // internal dispatcher thread that carries no MDC. The id must still ride through, because it
      // is captured when the continuation is REGISTERED, not when it is dispatched.
      val foreign = Executors.newSingleThreadExecutor()
      try
        val ec: ExecutionContext = MdcPropagatingExecutionContext(ExecutionContext.global)
        val p = Promise[Unit]()
        MDC.put("correlationId", "via-ask")
        val seen =
          try p.future.map(_ => Option(MDC.get("correlationId")))(ec)
          finally MDC.remove("correlationId")
        foreign.execute { () =>
          MDC.clear(); p.success(())
        } // complete off-thread, no MDC
        seen.futureValue shouldBe Some("via-ask")
      finally { val _ = foreign.shutdownNow() }
    }

    "not leak the value onto the worker thread after the task completes" in {
      val (delegate, close) = singleThreadEc()
      try
        val ec = MdcPropagatingExecutionContext(delegate)
        MDC.put("correlationId", "tenant-a")
        try Future(())(ec).futureValue
        finally MDC.remove("correlationId")
        // A later task with NO correlation id set must see a clean MDC on that reused thread.
        val leaked = Future(Option(MDC.get("correlationId")))(ec)
        leaked.futureValue shouldBe None
      finally close()
    }
  }
