package me.cference.artemis.config

import com.typesafe.config.Config

import scala.concurrent.duration.*

/**
 * PostgreSQL connection parameters for the read-side [[me.cference.artemis.projection]] repository,
 * which uses its own short-lived r2dbc connections rather than the persistence plugin's pool. Read
 * from the same `pekko.persistence.r2dbc.connection-factory` block as the journal so there is one
 * source of truth for the database coordinates.
 */
final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    connectTimeout: FiniteDuration
)

/**
 * Coordinates for the Apollo object store's gRPC endpoint, consumed by
 * [[me.cference.artemis.grpc.ApolloObjectClient]]. Read from `artemis.apollo`; every value is
 * env-overridable in `application.conf` so no endpoint is hard-coded in the client.
 */
final case class ApolloConfig(
    host: String,
    port: Int,
    useTls: Boolean
)

/**
 * Coordinates for the HermesMQ pub/sub gRPC endpoint, consumed by
 * [[me.cference.artemis.hermes.HermesClient]], plus the media-contract topic/subscription names.
 * Artemis PUBLISHES `ProcessMediaJob` to `topicMediaProcess` and CONSUMES `MediaProcessed` /
 * `MediaFailed` from `subMediaProcessed` / `subMediaFailed`. Read from `artemis.hermes`; every
 * value is env-overridable in `application.conf` so no endpoint or channel name is hard-coded.
 */
final case class HermesConfig(
    host: String,
    port: Int,
    useTls: Boolean,
    topicMediaProcess: String,
    subMediaProcessed: String,
    subMediaFailed: String,
    topicMediaTag: String,
    subTagsSuggested: String
)

/**
 * Post-processing near-duplicate detection knob. `hammingThreshold` is the maximum bit-difference
 * between two phashes for the newer post to be flagged a possible duplicate of the older. Read from
 * `artemis.dedup`, env-overridable.
 */
final case class DedupConfig(hammingThreshold: Int)

/**
 * Deletion-lifecycle knobs (deletion-lifecycle spec). `retention` is how long a soft-deleted post
 * is kept (still restorable) before auto-purge permanently deletes it + its blobs; `purgeInterval`
 * is how often the retention job scans for due posts. Read from `artemis.gc`, env-overridable.
 * (There is no orphan-sweep grace window: Apollo exposes no blob creation time, so the sweep
 * protects in-flight uploads via the referenced-post set — see `gc.OrphanSweep`.)
 */
final case class GcConfig(retention: FiniteDuration, purgeInterval: FiniteDuration)

/**
 * Bind coordinates for the HTTP API surface (health, metrics, search, catalog, media gateway). Read
 * from `artemis.http`; env-overridable so no host/port is hard-coded in
 * [[me.cference.artemis.Main]].
 */
final case class HttpConfig(host: String, port: Int)

/**
 * Runtime loop cadences and the startup readiness probe budget (service-runtime spec). Read from
 * `artemis.runtime`, all env-overridable:
 *   - `consumeInterval` — how often the Hermes consume loop pulls each result subscription.
 *   - `tagGraphRefresh` — how often the tag-graph cache reloads from Postgres (bounded staleness).
 *   - `readinessRetries` / `readinessRetryDelay` — the Postgres readiness probe budget before the
 *     boot aborts.
 */
final case class RuntimeConfig(
    consumeInterval: FiniteDuration,
    tagGraphRefresh: FiniteDuration,
    readinessRetries: Int,
    readinessRetryDelay: FiniteDuration,
    projectionInstances: Int
)

object AppConfig:

  def postgres(config: Config): PostgresConfig =
    val cf = config.getConfig("pekko.persistence.r2dbc.connection-factory")
    PostgresConfig(
      host = cf.getString("host"),
      port = cf.getInt("port"),
      database = cf.getString("database"),
      user = cf.getString("user"),
      password = cf.getString("password"),
      connectTimeout = cf.getDuration("connect-timeout").toMillis.millis
    )

  def apollo(config: Config): ApolloConfig =
    val ac = config.getConfig("artemis.apollo")
    ApolloConfig(
      host = ac.getString("host"),
      port = ac.getInt("port"),
      useTls = ac.getBoolean("tls")
    )

  def hermes(config: Config): HermesConfig =
    val hc = config.getConfig("artemis.hermes")
    HermesConfig(
      host = hc.getString("host"),
      port = hc.getInt("port"),
      useTls = hc.getBoolean("tls"),
      topicMediaProcess = hc.getString("topic-media-process"),
      subMediaProcessed = hc.getString("sub-media-processed"),
      subMediaFailed = hc.getString("sub-media-failed"),
      topicMediaTag = hc.getString("topic-media-tag"),
      subTagsSuggested = hc.getString("sub-tags-suggested")
    )

  def dedup(config: Config): DedupConfig =
    DedupConfig(hammingThreshold = config.getInt("artemis.dedup.hamming-threshold"))

  def gc(config: Config): GcConfig =
    val gc = config.getConfig("artemis.gc")
    GcConfig(
      retention = gc.getDuration("retention").toMillis.millis,
      purgeInterval = gc.getDuration("purge-interval").toMillis.millis
    )

  def http(config: Config): HttpConfig =
    val hc = config.getConfig("artemis.http")
    HttpConfig(host = hc.getString("host"), port = hc.getInt("port"))

  def runtime(config: Config): RuntimeConfig =
    val rc = config.getConfig("artemis.runtime")
    RuntimeConfig(
      consumeInterval = rc.getDuration("consume-interval").toMillis.millis,
      tagGraphRefresh = rc.getDuration("tag-graph-refresh").toMillis.millis,
      readinessRetries = rc.getInt("readiness-retries"),
      readinessRetryDelay = rc.getDuration("readiness-retry-delay").toMillis.millis,
      // Distributed projection workers; lives in cluster.conf alongside the sharding config.
      projectionInstances = config.getInt("artemis.projection.instances")
    )
