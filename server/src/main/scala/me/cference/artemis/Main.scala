package me.cference.artemis

import com.typesafe.config.ConfigFactory
import me.cference.artemis.build.BuildInfo
import me.cference.artemis.config.AppConfig
import me.cference.artemis.gc.{ApolloBlobStore, OrphanSweepService, PurgeService}
import me.cference.artemis.grpc.ApolloObjectClient
import me.cference.artemis.hermes.{
  HermesClient,
  HermesMediaJobPublisher,
  HermesMediaResultConsumer,
  HermesTagJobPublisher
}
import me.cference.artemis.http.{
  AdminDeletionRoutes,
  AdminGcRoutes,
  CatalogRoutes,
  DocsRoutes,
  HealthRoutes,
  HttpServer,
  MediaRoutes,
  MetricsRoutes,
  PoolReadService,
  RelatedTagsRoutes,
  ReprocessRoutes,
  RequestTracing,
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
import me.cference.artemis.media.{ApolloMediaSource, MediaResolver}
import me.cference.artemis.metrics.MetricsRegistry
import me.cference.artemis.persistence.{
  PersistenceReadiness,
  PoolSharding,
  PostSharding,
  SavedSearchesSharding
}
import me.cference.artemis.projection.{PoolProjection, PostProjection, ReadModelRepository}
import me.cference.artemis.reprocess.ReprocessService
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
    val gcCfg = AppConfig.gc(config)
    val reprocessCfg = AppConfig.reprocess(config)
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
    // Argus's suggestions → alias-merge → RecordSuggestions on the post (flags it for review),
    // stamping the current tagger version so a re-tag reprocess can find stale posts.
    val tagSuggestionHandler =
      new TagSuggestionHandler(postFor, graphCache.snapshot, reprocessCfg.taggerVersion)
    val consumer = new HermesMediaResultConsumer(
      hermesClient,
      hermesCfg.subMediaProcessed,
      hermesCfg.subMediaFailed,
      resultHandler,
      tagSuggestionsSub = hermesCfg.subTagsSuggested,
      tagHandler = tagSuggestionHandler.onSuggestions
    )

    // The upload spine: bytes → Apollo (content-addressed) → dedup on md5 (merge/restore/create).
    val uploadService = new UploadService(
      new ApolloObjectUploader(apolloClient),
      new HermesMediaJobPublisher(hermesClient, hermesCfg.topicMediaProcess),
      postFor,
      readModel.findByMd5
    )

    // The shared media blob store (Apollo-backed) for both GC paths: purge (delete a post's exact
    // keys) and the orphan sweep (list/delete unreferenced originals).
    val blobStore = new ApolloBlobStore(apolloClient, "media")

    // The deletion-lifecycle auto-purge job: soft-deleted posts past retention → delete blobs 1:1
    // + purge. Also serves the single-post immediate purge (admin-deletion) via `purgeNow`. Driven
    // on a schedule once dependencies are ready.
    val purgeService =
      new PurgeService(
        readModel.softDeletedBefore,
        readModel.purgeTargetFor,
        blobStore,
        postFor,
        gcCfg.retention
      )

    // The failed-upload orphan sweep (admin-gc): list `originals/`, subtract referenced md5s, and
    // delete the leftovers. Unscheduled — triggered only on demand via the admin GC surface.
    val orphanSweepService = new OrphanSweepService(readModel.referencedMd5s, blobStore)

    // The read surface: DSL search + facets over the read model, alias-resolving against the cache.
    val searchService = new SearchService(
      loadGraph = () => Future.successful(graphCache.current),
      expander = new ReadModelWildcardExpander(readModel, WildcardCap),
      execute = new SearchExecutor(readModel).search,
      computeFacets = new FacetExecutor(readModel).facets
    )

    // Compose the HTTP surface SEARCH-FIRST so `/posts` and `/posts/facets` are claimed before
    // Catalog's `/posts/{id}` segment route could capture them.
    val poolReadService = new PoolReadService(readModel)(using system.executionContext)
    val searchRoutes = new SearchRoutes(
      searchService.search,
      searchService.facets,
      readModel.autocompleteTags,
      poolReadService.listPools,
      poolReadService.poolPosts
    )
    val catalogRoutes = CatalogRoutes(postFor, poolFor)
    // Media resolution: the read model's STORED derivative refs first (the exact keys
    // Hephaestus reported), with the key-layout convention only as a fallback (e.g.
    // projection lag right after processing) — the convention alone once drifted from
    // Hephaestus's layout and every image 404'd.
    val resolveMediaRef: (String, String) => Future[codex.messages.v1.ObjectRef] =
      (md5, variant) =>
        readModel.mediaObjectRef(md5, variant).map {
          case Some((bucket, obj)) => codex.messages.v1.ObjectRef(bucket = bucket, `object` = obj)
          case None => MediaResolver.resolve(md5, variant)
        }
    val mediaRoutes = MediaRoutes(new ApolloMediaSource(apolloClient), resolveMediaRef)
    // Adapt the upload seam to the route's 3-arg shape (dedup metadata-merge params default empty).
    val uploadRoutes = UploadRoutes((bytes, ct, mt) => uploadService.upload(bytes, ct, mt))
    val savedSearchRoutes = SavedSearchRoutes(savedSearchesRef, searchService.search)
    val relatedTagsRoutes = RelatedTagsRoutes(readModel.relatedTags)
    val similarityService = new SimilarityService(readModel)
    val similarityRoutes =
      SimilarityRoutes(similarityService.similarTo, similarityService.reverseLookup)
    val reviewRoutes = ReviewRoutes(readModel.reviewQueue, postFor)

    // The manual reprocess orchestrator: resolve a selection (stale/id/DSL) and enqueue backfill
    // jobs to the lower-priority reprocess lanes. A DSL selection resolves through the search
    // service (one capped page); a bad query fails the Future → the route answers 400.
    val searchIds: String => Future[Seq[String]] = query =>
      searchService.search(query, None, None, reprocessCfg.maxSelect).map {
        case Right(page) =>
          if page.rows.sizeIs >= reprocessCfg.maxSelect then
            log.warn(
              "reprocess selection '{}' hit the max-select cap ({}); only the first page was " +
                "enqueued — narrow the query or raise artemis.reprocess.max-select",
              query,
              Integer.valueOf(reprocessCfg.maxSelect)
            )
          page.rows.map(_.id)
        case Left(err) => throw new IllegalArgumentException(err.message)
      }
    val reprocessService = new ReprocessService(
      stalePostIds = readModel.stalePostIds(_, _, reprocessCfg.maxSelect),
      searchIds = searchIds,
      infoFor = readModel.reprocessInfo,
      publishProcessJob =
        new HermesMediaJobPublisher(hermesClient, reprocessCfg.topicReprocess).publish,
      publishTagJob =
        new HermesTagJobPublisher(hermesClient, reprocessCfg.topicTagReprocess).publish,
      currentDerivativeVersion = reprocessCfg.derivativeSpecVersion,
      currentTaggerVersion = reprocessCfg.taggerVersion
    )
    val reprocessRoutes = ReprocessRoutes(reprocessService.reprocess)

    // The admin surfaces: per-post deletion lifecycle (soft-delete/restore/immediate purge) and
    // service-wide GC triggers (orphan sweep + on-demand retention purge). Composed after search so
    // the `/posts` segment routing stays consistent.
    val adminDeletionRoutes = AdminDeletionRoutes(postFor, purgeService.purgeNow)
    val adminGcRoutes =
      AdminGcRoutes(orphanSweepService.sweep, () => purgeService.purgeDue(java.time.Instant.now()))

    val apiRoutes: Route =
      HealthRoutes(BuildInfo.version, () => readiness.get()) ~
        MetricsRoutes(metrics) ~
        DocsRoutes.fromResources().routes ~
        searchRoutes.routes ~
        catalogRoutes.routes ~
        mediaRoutes.routes ~
        uploadRoutes.routes ~
        savedSearchRoutes.routes ~
        relatedTagsRoutes.routes ~
        similarityRoutes.routes ~
        reviewRoutes.routes ~
        reprocessRoutes.routes ~
        adminDeletionRoutes.routes ~
        adminGcRoutes.routes

    // Wrap the whole surface in request-tracing: mint a correlation id per request (ignoring any
    // client value), MDC it for the request's logs, access-log entry/completion, and echo
    // X-Correlation-Id on every response (incl. 4xx/5xx/404).
    val tracedRoutes: Route = RequestTracing.withCorrelationId(apiRoutes)

    HttpServer.bind(tracedRoutes, httpCfg.host, httpCfg.port).onComplete {
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
          gcCfg,
          graphCache,
          readModel,
          consumer,
          purgeService,
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
      gcCfg: me.cference.artemis.config.GcConfig,
      graphCache: TagGraphCache,
      readModel: ReadModelRepository,
      consumer: HermesMediaResultConsumer,
      purgeService: PurgeService,
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
        startPurgeLoop(purgeService, gcCfg.purgeInterval)
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

  /**
   * Drive the deletion-lifecycle auto-purge on a repeating tick: each pass purges soft-deleted
   * posts past their retention window (deleting blobs 1:1). A pass failure is logged and the loop
   * keeps ticking (a bad pass just retries next interval); `RestartSource` heals a stream-stage
   * failure. Unlike the consume loop, a terminated purge loop does not withdraw readiness — GC is
   * background housekeeping, not a serving dependency, so failing it must not flip `/health` DOWN.
   */
  private def startPurgeLoop(purgeService: PurgeService, interval: FiniteDuration)(using
      system: ActorSystem[?]
  ): Unit =
    import system.executionContext
    val restartSettings =
      RestartSettings(minBackoff = 5.seconds, maxBackoff = 5.minutes, randomFactor = 0.2)
    val _ = RestartSource
      .onFailuresWithBackoff(restartSettings) { () =>
        Source
          .tick(interval, interval, ())
          .mapAsync(1) { _ =>
            purgeService
              .purgeDue(java.time.Instant.now())
              .map { purged =>
                if purged > 0 then log.info("auto-purge: purged {} post(s) past retention", purged)
                purged
              }
              .recover { case ex =>
                log.warn("purge-loop pass failed ({}); continuing", ex.getMessage)
                0
              }
          }
      }
      .runWith(Sink.ignore)
