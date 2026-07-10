package me.cference.artemis.metrics

import io.prometheus.client.exporter.common.TextFormat
import io.prometheus.client.hotspot.DefaultExports
import io.prometheus.client.{Collector, CollectorRegistry, Counter, GaugeMetricFamily}

import java.io.StringWriter

/**
 * Owns the application `CollectorRegistry` and the metrics Artemis exposes (service-metrics spec):
 * JVM/process metrics (via `DefaultExports`), a build-info gauge, a readiness gauge that reads the
 * shared flag at scrape time, and application counters for the media consume loop (polls run and
 * result messages applied). Instrumentation call sites use the semantic `inc*` helpers so no
 * Prometheus types leak past this boundary; label values are drawn only from closed sets, bounding
 * cardinality.
 *
 * Read-model projection-offset lag is a deliberately-deferred metric (it needs an offset-store
 * query); the readiness gauge + JVM/process metrics cover runtime health at personal scale.
 */
final class MetricsRegistry(version: String, readiness: () => Boolean):

  val registry: CollectorRegistry = new CollectorRegistry(true)

  private val consumePolls: Counter = Counter
    .build()
    .name("artemis_consume_polls_total")
    .help("Media-result consume-loop poll attempts (success or failure).")
    .register(registry)

  private val consumeFailures: Counter = Counter
    .build()
    .name("artemis_consume_poll_failures_total")
    .help("Media-result consume-loop polls that failed (Hermes unreachable, etc.).")
    .register(registry)

  private val consumeApplied: Counter = Counter
    .build()
    .name("artemis_consume_messages_applied_total")
    .help("Media-result messages successfully handled and acked by the consume loop.")
    .register(registry)

  // Build-info: a constant 1-valued gauge carrying the version as a label so Grafana can correlate
  // behaviour with a deployed version.
  locally {
    val buildInfo = io.prometheus.client.Gauge
      .build()
      .name("artemis_build_info")
      .help("Build information; value is always 1, version carried as a label.")
      .labelNames("version")
      .register(registry)
    buildInfo.labels(version).set(1.0)
  }

  // Readiness: a custom collector so the value always reflects the shared flag at scrape time
  // rather than the last write.
  registry.register(
    new Collector:
      def collect(): java.util.List[Collector.MetricFamilySamples] =
        java.util.Collections.singletonList(
          new GaugeMetricFamily(
            "artemis_ready",
            "1 when the service is ready to serve, else 0.",
            if readiness() then 1.0 else 0.0
          )
        )
  )

  DefaultExports.register(registry)

  /** Record one completed consume-loop poll that applied `applied` messages. */
  def observeConsumePoll(applied: Int): Unit =
    consumePolls.inc()
    if applied > 0 then consumeApplied.inc(applied.toDouble)

  /**
   * Record one consume-loop poll that failed. Counts the attempt too, so `polls_total` reflects
   * every attempt and a persistently-failing loop is visible (failures advancing, applied flat)
   * rather than looking idle-but-healthy.
   */
  def observeConsumePollFailure(): Unit =
    consumePolls.inc()
    consumeFailures.inc()

  /** Render the registry in Prometheus text exposition format (version 0.0.4). */
  def scrape(): String =
    val writer = new StringWriter()
    TextFormat.write004(writer, registry.metricFamilySamples())
    writer.toString

/** The content type of the text exposition format returned by [[MetricsRegistry.scrape]]. */
object MetricsRegistry:
  val ContentType: String = TextFormat.CONTENT_TYPE_004
