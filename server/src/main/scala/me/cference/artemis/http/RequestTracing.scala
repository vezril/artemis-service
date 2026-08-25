package me.cference.artemis.http

import me.cference.artemis.tracing.CorrelationId
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.{LoggerFactory, MDC}

/**
 * Correlation + access logging for the HTTP route tree (request-tracing). Wrapping the routes with
 * [[withCorrelationId]] MINTS a fresh id — the HTTP API is untrusted ingress, so any
 * client-supplied `X-Correlation-Id` is ignored (anti-injection) — puts it in the MDC for the
 * duration of the request's (synchronous) route execution, access-logs entry + completion (method,
 * path, status), and echoes `X-Correlation-Id` on every response. The inner routes are sealed BELOW
 * the response mapping, so rejections and exceptions are turned into responses first — hence the
 * header lands on 4xx/5xx and 404s too, not only explicit completions.
 *
 * MDC is held across the whole synchronous run of the sealed inner route (not merely around the log
 * lines, as Apollo's simpler edge does), so a handler that kicks off outbound work while the route
 * directive is still executing — the upload path submitting its first `Future` on an
 * [[me.cference.artemis.tracing.MdcPropagatingExecutionContext]] — snapshots the id and carries it
 * through to the published `ProcessMediaJob.correlation_id` and the Apollo `x-correlation-id`
 * metadata. Async continuations are covered by that propagating EC, not by this thread-local scope.
 */
object RequestTracing:

  private val log = LoggerFactory.getLogger("me.cference.artemis.http.access")

  def withCorrelationId(inner: Route): Route =
    val sealedRoute = Route.seal(inner) // sealed once (independent of request/id), not per request
    extractRequest { request =>
      val id = CorrelationId.mint() // mint, ignore any client X-Correlation-Id (anti-injection)
      val method = request.method.value
      val path = request.uri.path.toString
      mapResponse { response =>
        withMdc(id)(log.info(s"← HTTP $method $path ${response.status.intValue}"))
        response.addHeader(RawHeader(CorrelationId.HttpHeader, id))
      } { ctx =>
        // Hold the id across the synchronous route run (entry log + directive tree, where outbound
        // work is submitted); `withMdc` restores the thread's prior MDC once this returns the
        // still-pending Future — async continuations ride the propagating EC, not this scope.
        withMdc(id) {
          log.info(s"→ HTTP $method $path")
          sealedRoute(ctx)
        }
      }
    }

  private def withMdc[A](id: String)(body: => A): A =
    MDC.put(CorrelationId.MdcKey, id)
    try body
    finally MDC.remove(CorrelationId.MdcKey)
