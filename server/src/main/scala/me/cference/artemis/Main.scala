package me.cference.artemis

import com.typesafe.config.ConfigFactory
import me.cference.artemis.build.BuildInfo
import me.cference.artemis.config.AppConfig
import me.cference.artemis.grpc.ApolloObjectClient
import me.cference.artemis.hermes.{
  HermesClient,
  HermesMediaJobPublisher,
  HermesMediaResultConsumer,
  HermesTagJobPublisher
}
import me.cference.artemis.http.{
  CatalogRoutes,
  HealthRoutes,
  HttpServer,
  MediaRoutes,
  MetricsRoutes,
  RelatedTagsRoutes,
  ReviewRoutes,
  SavedSearchRoutes,
  SearchRoutes,
  SimilarityRoutes,
  UploadRoutes
}
import me.cference.artemis.ingest.{
  ApolloObjectUploader,
  InMemoryProcessedJobs,
  MediaResultHandler,
  ReadModelNearDuplicates,
  TagSuggestionHandler,
  UploadService
}
import me.cference.artemis.media.ApolloMediaSource
import me.cference.artemis.metrics.MetricsRegistry
import me.cference.artemis.persistence.{
  PersistenceReadiness,
  PoolSharding,
  PostSharding,
  SavedSearchesSharding
}
import me.cference.artemis.projection.{PoolProjection, PostProjection, ReadModelRepository}
import me.cference.artemis.search.{
  FacetExecutor,
  ReadModelWildcardExpander,
  SearchExecutor,
  SearchService
}
import me.cference.artemis.similarity.SimilarityService
import me.cference.artemis.tags.{TagGraphCache, TagGraphRepository}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, ShardedDaemonProcess}
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.apache.pekko.projection.ProjectionBehavior
import org.apache.pekko.stream.scaladsl.{RestartSource, Sink, Source}
import org.apache.pekko.stream.{Materializer, RestartSettings}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/**
 * Entry point (service-runtime spec). Forms a Pekko cluster (Management + Bootstrap config
 * discovery, cluster of one by default), hosts the `Post`/`Pool` aggregates via Cluster Sharding
 * (single writer per id), binds the composed HTTP surface, and — once Postgres is ready — loads the
 * tag-graph cache, starts the read-model projections (ShardedDaemonProcess) and the Hermes
 * media-result consume loop, and flips readiness to UP. Any bind or dependency failure exits
 * non-zero; SIGTERM is handled by Coordinated Shutdown (readiness withdrawal, drain, shard
 * handoff).
 */
object Main:
  private val log = LoggerFactory.getLogger(getClass)

  /** Wildcard-expansion cap: `tag*` patterns matching more than this are rejected as too broad. */
  private val WildcardCap = 200

  def main(args: Array[String]): Unit =
    val config = ConfigFactory.load()
    val httpCfg = AppConfig.http(config)
    val pgCfg = AppConfig.postgres(config)
    val apolloCfg = AppConfig.apollo(config)
    val hermesCfg = AppConfig.hermes(config)
    val dedupCfg = AppConfig.dedup(config)
    val runtimeCfg = AppConfig.runtime(config)

    given system: ActorSystem[Nothing] =
      ActorSystem[Nothing](Behaviors.empty[Nothing], "artemis", config)
    import system.executionContext
    given Timeout = Timeout(10.seconds)
    given Materializer = Materializer(system)

    val readiness = new AtomicBoolean(false)
    val metrics = new MetricsRegistry(BuildInfo.version, () => readiness.get())

    // Cluster formation (config discovery; a lone node forms a cluster of one).
    val _ = PekkoManagement(system).start()
    ClusterBootstrap(system).start()

    // Read model + tag-graph cache. The cache's snapshot supplier is captured by the post entities
    // (canonicalization) and the search service (alias resolution); it is empty until the first
    // refresh, which the readiness chain runs before flipping UP.
    val readModel = new ReadModelRepository(pgCfg)
    val graphCache = new TagGraphCache(() => new TagGraphRepository(pgCfg).loadGraph())

    // Cluster Sharding: single writer per id. The HTTP/ingest layers address entities via these
    // ref factories (an EntityRef is a RecipientRef).
    val postShard: ClusterSharding = PostSharding.init(system, graphCache.snapshot)
    val poolShard: ClusterSharding = PoolSharding.init(system)
    val savedShard: ClusterSharding = SavedSearchesSharding.init(system)
    val postFor = (id: String) => PostSharding.entityRef(postShard, id)
    val poolFor = (id: String) => PoolSharding.entityRef(poolShard, id)
    val savedSearchesRef = () => SavedSearchesSharding.entityRef(savedShard)

    // gRPC clients (app-lifetime singletons; channels released on system termination).
    val apolloClient = new ApolloObjectClient(apolloCfg)
    val hermesClient = new HermesClient(hermesCfg)

    // The consume spine: Hephaestus results → the sharded post entities (idempotent per jobId).
    // On activation it also publishes a TagJob (auto-tagging) to Argus, best-effort.
    val resultHandler = new MediaResultHandler(
      new InMemoryProcessedJobs,
      postFor,
      new ReadModelNearDuplicates(readModel, dedupCfg.hammingThreshold),
      new HermesTagJobPublisher(hermesClient, hermesCfg.topicMediaTag)
    )
    // Argus's suggestions → alias-merge → RecordSuggestions on the post (flags it for review).
    val tagSuggestionHandler = new TagSuggestionHandler(postFor, graphCache.snapshot)
    val consumer = new HermesMediaResultConsumer(
      hermesClient,
      hermesCfg.subMediaProcessed,
      hermesCfg.subMediaFailed,
      resultHandler,
      tagSuggestionsSub = hermesCfg.subTagsSuggested,
      tagHandler = tagSuggestionHandler.onSuggestions
    )

    // The upload spine: bytes → Apollo (content-addressed) → pending post → ProcessMediaJob.
    val uploadService = new UploadService(
      new ApolloObjectUploader(apolloClient),
      new HermesMediaJobPublisher(hermesClient, hermesCfg.topicMediaProcess),
      postFor
    )

    // The read surface: DSL search + facets over the read model, alias-resolving against the cache.
    val searchService = new SearchService(
      loadGraph = () => Future.successful(graphCache.current),
      expander = new ReadModelWildcardExpander(readModel, WildcardCap),
      execute = new SearchExecutor(readModel).search,
      computeFacets = new FacetExecutor(readModel).facets
    )

    // Compose the HTTP surface SEARCH-FIRST so `/posts` and `/posts/facets` are claimed before
    // Catalog's `/posts/{id}` segment route could capture them.
    val searchRoutes = new SearchRoutes(
      searchService.search,
      searchService.facets,
      readModel.autocompleteTags
    )
    val catalogRoutes = CatalogRoutes(postFor, poolFor)
    val mediaRoutes = MediaRoutes(new ApolloMediaSource(apolloClient))
    val uploadRoutes = UploadRoutes(uploadService.upload)
    val savedSearchRoutes = SavedSearchRoutes(savedSearchesRef, searchService.search)
    val relatedTagsRoutes = RelatedTagsRoutes(readModel.relatedTags)
    val similarityService = new SimilarityService(readModel)
    val similarityRoutes =
      SimilarityRoutes(similarityService.similarTo, similarityService.reverseLookup)
    val reviewRoutes = ReviewRoutes(readModel.reviewQueue, postFor)
    val apiRoutes: Route =
      HealthRoutes(BuildInfo.version, () => readiness.get()) ~
        MetricsRoutes(metrics) ~
        searchRoutes.routes ~
        catalogRoutes.routes ~
        mediaRoutes.routes ~
        uploadRoutes.routes ~
        savedSearchRoutes.routes ~
        relatedTagsRoutes.routes ~
        similarityRoutes.routes ~
        reviewRoutes.routes

    HttpServer.bind(apiRoutes, httpCfg.host, httpCfg.port).onComplete {
      case Success(binding) =>
        HttpServer.wireShutdown(binding, readiness)
        log.info(
          "Artemis {} bound HTTP :{}; checking dependencies…",
          BuildInfo.version,
          Integer.valueOf(binding.localAddress.getPort)
        )
        awaitDependenciesThenServe(
          pgCfg,
          runtimeCfg,
          graphCache,
          readModel,
          consumer,
          metrics,
          readiness
        )
      case Failure(ex) =>
        log.error(s"Failed to bind HTTP :${httpCfg.port} — ${ex.getMessage}", ex)
        system.terminate()
        System.exit(1)
    }

  /**
   * Gate serving on Postgres readiness: probe the DB, load the tag graph once, start the
   * projections and the consume loop, schedule the graph refresh, then flip readiness UP. A failed
   * dependency step aborts the boot (non-zero exit) rather than serving a half-wired process.
   */
  private def awaitDependenciesThenServe(
      pgCfg: me.cference.artemis.config.PostgresConfig,
      runtimeCfg: me.cference.artemis.config.RuntimeConfig,
      graphCache: TagGraphCache,
      readModel: ReadModelRepository,
      consumer: HermesMediaResultConsumer,
      metrics: MetricsRegistry,
      readiness: AtomicBoolean
  )(using system: ActorSystem[Nothing]): Unit =
    import system.executionContext
    val ready =
      for
        _ <- PersistenceReadiness.check(
          pgCfg,
          runtimeCfg.readinessRetries,
          runtimeCfg.readinessRetryDelay
        )
        _ <- graphCache.refresh()
      yield ()

    ready.onComplete {
      case Success(_) =>
        startProjections(readModel, runtimeCfg.projectionInstances)
        val _ = graphCache.startScheduledRefresh(runtimeCfg.tagGraphRefresh)
        startConsumeLoop(consumer, metrics, runtimeCfg.consumeInterval, readiness)
        readiness.set(true)
        log.info("Postgres ready, tag graph loaded — Artemis is UP")
      case Failure(ex) =>
        log.error(s"Startup dependency step failed — ${ex.getMessage}", ex)
        system.terminate()
        System.exit(1)
    }

  /** Distribute each read-model projection across the cluster: one instance per slice range. */
  private def startProjections(readModel: ReadModelRepository, instances: Int)(using
      system: ActorSystem[?]
  ): Unit =
    val postRanges = PostProjection.sliceRanges(instances)
    ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
      name = "post-projection",
      numberOfInstances = postRanges.size,
      behaviorFactory = i => ProjectionBehavior(PostProjection.forRange(readModel, postRanges(i))),
      stopMessage = ProjectionBehavior.Stop
    )
    val poolRanges = PoolProjection.sliceRanges(instances)
    ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
      name = "pool-projection",
      numberOfInstances = poolRanges.size,
      behaviorFactory = i => ProjectionBehavior(PoolProjection.forRange(readModel, poolRanges(i))),
      stopMessage = ProjectionBehavior.Stop
    )

  /**
   * Drive the Hermes consume loop on a repeating tick: each poll pulls, decodes, applies, and acks
   * a batch from the result subscriptions. A per-poll failure is recovered (logged + counted) so
   * the loop keeps ticking; a stream-stage failure (e.g. a synchronous throw on a closed channel)
   * is healed by `RestartSource` with backoff. As a last resort, if the loop ever terminates for
   * good, readiness is withdrawn so `/health` flips DOWN and an orchestrator restarts the node
   * rather than leaving every post wedged in `pending`. The stream is torn down by Coordinated
   * Shutdown on a normal shutdown.
   */
  private def startConsumeLoop(
      consumer: HermesMediaResultConsumer,
      metrics: MetricsRegistry,
      interval: FiniteDuration,
      readiness: AtomicBoolean
  )(using system: ActorSystem[?]): Unit =
    import system.executionContext
    val restartSettings = RestartSettings(
      minBackoff = 1.second,
      maxBackoff = 30.seconds,
      randomFactor = 0.2
    )
    val done = RestartSource
      .onFailuresWithBackoff(restartSettings) { () =>
        Source
          .tick(interval, interval, ())
          .mapAsync(1) { _ =>
            consumer
              .pollOnce()
              .map { applied =>
                metrics.observeConsumePoll(applied)
                applied
              }
              .recover { case ex =>
                log.warn("consume-loop poll failed ({}); continuing", ex.getMessage)
                metrics.observeConsumePollFailure()
                0
              }
          }
      }
      .runWith(Sink.ignore)
    done.onComplete { outcome =>
      readiness.set(false)
      log.error("consume loop terminated unexpectedly ({}); readiness withdrawn", outcome)
    }
