package me.cference.artemis.http

import me.cference.artemis.metrics.MetricsRegistry
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import java.nio.charset.StandardCharsets

/**
 * The Prometheus scrape surface (service-metrics spec). `GET /metrics` renders the application
 * registry in text exposition format. It is deliberately unauthenticated — the endpoint carries
 * only operational telemetry, never catalog data — mirroring the `/health` carve-out.
 */
object MetricsRoutes:

  private val contentType: ContentType =
    ContentType.parse(MetricsRegistry.ContentType).getOrElse(ContentTypes.`text/plain(UTF-8)`)

  def apply(metrics: MetricsRegistry): Route =
    path("metrics") {
      get {
        val body = metrics.scrape().getBytes(StandardCharsets.UTF_8)
        complete(HttpResponse(entity = HttpEntity(contentType, body)))
      }
    }
