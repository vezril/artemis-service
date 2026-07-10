package me.cference.artemis

import apollostorage.grpc.{PutHeader, PutObjectResponse}
import codex.messages.v1.{Derivative as WireDerivative, MediaMetadata, MediaProcessed, ObjectRef}
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.google.protobuf.ByteString as ProtoBytes
import com.typesafe.config.{Config, ConfigFactory}
import me.cference.artemis.config.{HermesConfig, PostgresConfig}
import me.cference.artemis.domain.*
import me.cference.artemis.domain.PostCommand.ChangeTags
import me.cference.artemis.hermes.{HermesClient, HermesMediaJobPublisher, HermesMediaResultConsumer}
import me.cference.artemis.ingest.{
  InMemoryProcessedJobs,
  MediaResultHandler,
  ObjectUploader,
  ReadModelNearDuplicates,
  UploadService
}
import me.cference.artemis.messages.MediaMessages
import me.cference.artemis.persistence.{PostEntity, PostSharding}
import me.cference.artemis.projection.{PostProjection, ReadModelRepository}
import me.cference.artemis.search.{
  FacetExecutor,
  ReadModelWildcardExpander,
  SearchExecutor,
  SearchService
}
import me.cference.hermesmq.grpc.*
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.apache.pekko.grpc.scaladsl.Metadata
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.projection.ProjectionBehavior
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.{ByteString, Timeout}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.io.Source as ScalaSource
import scala.util.Using

/**
 * End-to-end verification of the assembled media spine (internals 6.1 / service-runtime spec).
 * Against a real Postgres (testcontainers) with Apollo and HermesMQ doubled in-process, it wires
 * the runtime's own components — cluster-sharded post entities, the read-model projection, the real
 * `UploadService` (fake Apollo upload + real Hermes publish), and the real
 * `HermesMediaResultConsumer` — and drives one post through the whole pipeline:
 *
 * upload → pending → (simulated Hephaestus `MediaProcessed`) → consumed → active → searchable.
 *
 * This is not a stubbed unit path: the consume loop pulls from the in-process broker, decodes the
 * canonical JSON, applies the command through the sharded entity, the projection folds the events
 * into the `posts` read table, and the search DSL returns the post — proving the seams compose.
 */
final class RunnableServiceE2EIT
    extends AnyWordSpec
    with Matchers
    with ForAllTestContainer
    with ScalaFutures
    with Eventually
    with BeforeAndAfterAll:

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"),
    databaseName = "artemis",
    username = "artemis",
    password = "artemis"
  )

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(30, Seconds), interval = Span(300, Millis))

  private given Timeout = Timeout(10.seconds)
  private var testKit: ActorTestKit = scala.compiletime.uninitialized
  private var sharding: ClusterSharding = scala.compiletime.uninitialized
  private var repo: ReadModelRepository = scala.compiletime.uninitialized

  private val TopicProcess = "media.process"
  private val TopicProcessed = "media.processed"
  private val TopicFailed = "media.failed"
  private val SubProcessed = "artemis.media.processed"
  private val SubFailed = "artemis.media.failed"

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    testKit = ActorTestKit("artemis-e2e", config())
    val cluster = Cluster(testKit.system)
    cluster.manager ! Join(cluster.selfMember.address)
    eventually(cluster.selfMember.status shouldBe MemberStatus.Up)
    sharding = PostSharding.init(testKit.system, () => TagGraph.empty)
    repo = new ReadModelRepository(pgConfig)(using testKit.system.executionContext)
    // Run the post read-model projection for the duration of the suite.
    val _ = testKit.spawn(ProjectionBehavior(PostProjection(repo)(using testKit.system)))

  override def beforeStop(): Unit =
    if testKit != null then testKit.shutdownTestKit() // scalafix:ok DisableSyntax.null

  private def pgConfig =
    PostgresConfig(
      container.host,
      container.mappedPort(5432),
      container.databaseName,
      container.username,
      container.password,
      5.seconds
    )

  private def config(): Config =
    ConfigFactory
      .parseString(s"""
        pekko.actor.provider = "cluster"
        pekko.remote.artery.canonical.hostname = "127.0.0.1"
        pekko.remote.artery.canonical.port = 0
        pekko.cluster.jmx.multi-mbeans-in-same-jvm = on
        pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
        pekko.http.server.preview.enable-http2 = on
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
        pekko.persistence.r2dbc.connection-factory {
          host = "${container.host}"
          port = ${container.mappedPort(5432)}
          database = "${container.databaseName}"
          user = "${container.username}"
          password = "${container.password}"
        }
      """)
      .withFallback(ConfigFactory.parseResources("persistence.conf"))
      .withFallback(ConfigFactory.parseResources("serialization.conf"))
      .withFallback(ConfigFactory.load())
      .resolve()

  private def applySchema(): Unit =
    val sql = Using.resource(ScalaSource.fromResource("create_tables_postgres.sql"))(_.mkString)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      Using.resource(conn.createStatement()) { st =>
        val _ = st.execute(sql)
      }
    }

  private def postFor(id: String) = PostSharding.entityRef(sharding, id)

  /** Bind the in-process Hermes double and point a real [[HermesClient]] at it. */
  private def hermesClientFor(broker: FakeBroker): HermesClient =
    val handler = PubSubServicePowerApiHandler(broker)(testKit.system)
    val binding = Http()(testKit.system).newServerAt("127.0.0.1", 0).bind(handler).futureValue
    new HermesClient(
      HermesConfig(
        host = "127.0.0.1",
        port = binding.localAddress.getPort,
        useTls = false,
        topicMediaProcess = TopicProcess,
        subMediaProcessed = SubProcessed,
        subMediaFailed = SubFailed
      )
    )(using testKit.system)

  "The assembled Artemis runtime" should {

    "carry a post upload → pending → processed → active → searchable" in {
      val sys = testKit.system
      given org.apache.pekko.actor.typed.ActorSystem[Nothing] = sys
      given scala.concurrent.ExecutionContext = sys.executionContext

      val broker = new FakeBroker(Map(SubProcessed -> TopicProcessed, SubFailed -> TopicFailed))
      val hermes = hermesClientFor(broker)

      // --- upload: real UploadService, fake Apollo put + real Hermes publish. ---
      val uploader = new FakeUploader
      val publisher = new HermesMediaJobPublisher(hermes, TopicProcess)
      val upload = new UploadService(
        uploader,
        publisher,
        postFor,
        genId = () => "e2e-1",
        genJobId = () => "e2e-job-1"
      )
      val result = upload
        .upload(Source.single(ByteString("some png bytes")), "image/png", "image")
        .futureValue
      result.postId.value shouldBe "e2e-1"
      result.status shouldBe "pending"

      // The upload created a pending post…
      state("e2e-1") shouldBe a[PostState.Pending]
      // …and published a ProcessMediaJob for it (upload spine reached the broker).
      broker.messages(TopicProcess) should have size 1

      // --- simulate Hephaestus: a MediaProcessed result lands on the result topic. ---
      val _ = broker.enqueue(TopicProcessed, processedJson("e2e-job-1", "e2e-1", 800, 600))

      // --- consume: the real consumer drives the pending post to active and acks. ---
      // Same handler wiring as Main (incl. the ReadModelNearDuplicates post-processing branch).
      val consumer = new HermesMediaResultConsumer(
        hermes,
        SubProcessed,
        SubFailed,
        new MediaResultHandler(
          new InMemoryProcessedJobs,
          postFor,
          new ReadModelNearDuplicates(repo, 10)
        )
      )
      consumer.pollOnce().futureValue shouldBe 1
      broker.messages(TopicProcessed) shouldBe empty // acked

      state("e2e-1") match
        case PostState.Active(_, media, _) =>
          media.dimensions.width shouldBe 800
          media.dimensions.height shouldBe 600
        case other => fail(s"expected Active after processing, got $other")

      // Tag it so it is findable by the DSL (a search needs a positive tag anchor).
      postFor("e2e-1")
        .askWithStatus[org.apache.pekko.Done](replyTo =>
          PostEntity
            .Execute(ChangeTags(Set(Tag.unsafe("landscape")), java.time.Instant.now()), replyTo)
        )
        .futureValue

      // --- searchable: the projection folds the events; the search DSL returns the post. ---
      val searchService = new SearchService(
        loadGraph = () => Future.successful(TagGraph.empty),
        expander = new ReadModelWildcardExpander(repo, 200),
        execute = new SearchExecutor(repo).search,
        computeFacets = new FacetExecutor(repo).facets
      )
      eventually {
        val page = searchService.search("landscape", None, None, 40).futureValue
        page.map(_.rows.map(_.id)) match
          case Right(ids) => ids should contain("e2e-1")
          case Left(err) => fail(s"search failed: ${err.message}")
      }
    }
  }

  private def state(id: String): PostState =
    postFor(id).ask[PostState](PostEntity.Get(_)).futureValue

  private def processedJson(jobId: String, postId: String, w: Int, h: Int): ProtoBytes =
    val m = MediaProcessed(
      jobId = jobId,
      postId = postId,
      status = "ok",
      metadata = Some(MediaMetadata(width = w, height = h, md5 = "abc", filetype = "png")),
      phash = "f00dcafe",
      derivatives =
        Seq(WireDerivative(kind = "thumb", ref = Some(ObjectRef("media", s"$postId/thumb.webp")))),
      specVersion = 1
    )
    ProtoBytes.copyFrom(MediaMessages.toJson(m), UTF_8)

  /** Fake Apollo upload: records nothing, always succeeds (no real object store in the e2e). */
  final private class FakeUploader extends ObjectUploader:
    def put(header: PutHeader, chunks: Source[ByteString, ?]): Future[PutObjectResponse] =
      Future.successful(
        PutObjectResponse(generation = 1L, crc32C = "", md5 = header.expectedMd5, size = 0L)
      )

  /**
   * In-process stand-in for HermesMQ's PubSubService (same shape as `HermesMediaSpec`'s): `publish`
   * appends to a per-topic queue, `pull` returns up to `max` for the subscription's bound topic,
   * `ack` removes them. Admin/streaming RPCs are unused here.
   */
  final private class FakeBroker(subscriptions: Map[String, String]) extends PubSubServicePowerApi:
    private val counter = new AtomicLong(0)
    private val queues = TrieMap.empty[String, TrieMap[String, PulledMessage]]

    private def queue(topic: String): TrieMap[String, PulledMessage] =
      queues.getOrElseUpdate(topic, TrieMap.empty)

    private def topicOf(subscriptionId: String): String =
      subscriptions.getOrElse(subscriptionId, subscriptionId)

    def enqueue(topic: String, payload: ProtoBytes): String =
      val id = s"m-${counter.incrementAndGet()}"
      val _ = queue(topic).put(id, PulledMessage(ackId = id, message = Some(Message(id, payload))))
      id

    def messages(topic: String): Seq[PulledMessage] = queue(topic).values.toSeq

    override def publish(in: PublishRequest, metadata: Metadata): Future[PublishResponse] =
      val id = enqueue(in.topicId, in.payload)
      Future.successful(PublishResponse(messageId = id, deduplicated = false))

    override def pull(in: PullRequest, metadata: Metadata): Future[PullResponse] =
      val msgs = queue(topicOf(in.subscriptionId)).values.take(in.max).toSeq
      Future.successful(PullResponse(messages = msgs))

    override def ack(in: AckRequest, metadata: Metadata): Future[AckResponse] =
      val q = queue(topicOf(in.subscriptionId))
      val removed = in.ackIds.filter(id => q.remove(id).isDefined)
      Future.successful(AckResponse(acknowledged = removed, unknown = in.ackIds.diff(removed)))

    private def unsupported[A]: Future[A] =
      Future.failed(new UnsupportedOperationException("not used by RunnableServiceE2EIT"))

    override def createSubscription(
        in: CreateSubscriptionRequest,
        metadata: Metadata
    ): Future[CreateSubscriptionResponse] = unsupported

    override def modifyAckDeadline(
        in: ModifyAckDeadlineRequest,
        metadata: Metadata
    ): Future[ModifyAckDeadlineResponse] = unsupported

    override def streamMessages(
        in: StreamRequest,
        metadata: Metadata
    ): Source[PulledMessage, NotUsed] =
      Source.empty

    override def consume(
        in: Source[ConsumeRequest, NotUsed],
        metadata: Metadata
    ): Source[PulledMessage, NotUsed] = Source.empty
