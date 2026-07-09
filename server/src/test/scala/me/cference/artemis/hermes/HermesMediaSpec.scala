package me.cference.artemis.hermes

import codex.messages.v1.{
  Derivative as WireDerivative,
  JobError,
  MediaFailed,
  MediaMetadata,
  MediaProcessed,
  ObjectRef,
  ProcessMediaJob
}
import com.google.protobuf.ByteString as ProtoBytes
import com.typesafe.config.ConfigFactory
import me.cference.artemis.config.{AppConfig, HermesConfig}
import me.cference.artemis.domain.PostCommand.CreatePost
import me.cference.artemis.domain.{Filetype, Md5, PostId, PostState}
import me.cference.artemis.ingest.{InMemoryProcessedJobs, MediaResultHandler}
import me.cference.artemis.messages.MediaMessages
import me.cference.artemis.persistence.PostEntity
import me.cference.hermesmq.grpc.*
import org.apache.pekko.Done
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.RecipientRef
import org.apache.pekko.grpc.scaladsl.Metadata
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * The concrete HermesMQ transport (OpenSpec tasks 4.1 publish / 4.2 consume). Config-reading is
 * asserted directly; the publish/consume adapters are exercised against an in-process Hermes double
 * bound on an ephemeral port, mirroring [[me.cference.artemis.grpc.ApolloObjectClientSpec]]'s
 * handler+client pattern and [[me.cference.artemis.ingest.MediaResultHandlerSpec]]'s
 * persistence-testkit journal (no Docker).
 */
final class HermesMediaSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(
          """
        pekko.http.server.preview.enable-http2 = on
        # Don't let the shared runtime config's coordinated-shutdown kill the forked test JVM
        # when the testkit terminates this ActorSystem.
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
        """
        )
        .withFallback(PersistenceTestKitPlugin.config)
        .withFallback(PersistenceTestKitSnapshotPlugin.config)
        .withFallback(ConfigFactory.load())
    )
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(100, Millis))

  private given Timeout = Timeout(3.seconds)

  "AppConfig.hermes" should {
    "read host, port, tls and the topic/subscription names from HOCON" in {
      val hocon = ConfigFactory
        .parseString(
          """artemis.hermes {
            host = "hermes.internal"
            port = 9450
            tls = true
            topic-media-process = "media.process"
            sub-media-processed = "artemis.media.processed"
            sub-media-failed = "artemis.media.failed"
          }"""
        )
        .resolve()
      val cfg = AppConfig.hermes(hocon)
      cfg.host shouldBe "hermes.internal"
      cfg.port shouldBe 9450
      cfg.useTls shouldBe true
      cfg.topicMediaProcess shouldBe "media.process"
      cfg.subMediaProcessed shouldBe "artemis.media.processed"
      cfg.subMediaFailed shouldBe "artemis.media.failed"
    }
  }

  private val TopicProcess = "media.process"
  private val TopicProcessed = "media.processed"
  private val TopicFailed = "media.failed"
  private val SubProcessed = "artemis.media.processed"
  private val SubFailed = "artemis.media.failed"

  /** Bind an in-process Hermes double on an ephemeral port and point a [[HermesClient]] at it. */
  private def clientFor(broker: FakeBroker): HermesClient =
    val handler = PubSubServicePowerApiHandler(broker)(system)
    val binding = Http()(system).newServerAt("127.0.0.1", 0).bind(handler).futureValue
    val port = binding.localAddress.getPort
    port should be > 0
    new HermesClient(
      HermesConfig(
        host = "127.0.0.1",
        port = port,
        useTls = false,
        topicMediaProcess = TopicProcess,
        subMediaProcessed = SubProcessed,
        subMediaFailed = SubFailed
      )
    )

  private def aProcessMediaJob: ProcessMediaJob =
    ProcessMediaJob(
      jobId = "j-1",
      postId = "p-1",
      source = Some(ObjectRef("media", "p-1/source.png")),
      mediaType = "image",
      contentType = "image/png",
      want = Seq("thumb", "phash")
    )

  "HermesMediaJobPublisher" should {
    "publish a ProcessMediaJob as camelCase JSON to the media-process topic" in {
      val broker = new FakeBroker(subscriptions = Map.empty)
      val client = clientFor(broker)
      val publisher =
        new HermesMediaJobPublisher(client, TopicProcess)(using system.executionContext)

      publisher.publish(aProcessMediaJob).futureValue

      val queued = broker.messages(TopicProcess)
      queued should have size 1
      val payload = queued.head.message.flatMap(m => Option(m.payload)).getOrElse(ProtoBytes.EMPTY)
      val decoded = MediaMessages.fromJson[ProcessMediaJob](payload.toStringUtf8)
      decoded shouldBe aProcessMediaJob
    }
  }

  @volatile private var posts: Map[String, RecipientRef[PostEntity.Command]] = Map.empty
  private def postFor(id: String): RecipientRef[PostEntity.Command] =
    synchronized {
      posts.getOrElse(
        id, {
          val ref = testKit.spawn(PostEntity(PostId.unsafe(id)))
          posts = posts.updated(id, ref)
          ref
        }
      )
    }

  private def createPending(id: String): Unit =
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    postFor(id) ! PostEntity.Execute(
      CreatePost(PostId.unsafe(id), Md5("abc"), Filetype("image/png"), Instant.now()),
      probe.ref
    )
    val _ = probe.receiveMessage()

  private def getState(id: String): PostState =
    val probe = testKit.createTestProbe[PostState]()
    postFor(id) ! PostEntity.Get(probe.ref)
    probe.receiveMessage()

  private def processedJson(jobId: String, postId: String, width: Int, height: Int): ProtoBytes =
    val m = MediaProcessed(
      jobId = jobId,
      postId = postId,
      status = "ok",
      metadata = Some(MediaMetadata(width = width, height = height, md5 = "abc", filetype = "png")),
      phash = "ph-1",
      derivatives =
        Seq(WireDerivative(kind = "thumb", ref = Some(ObjectRef("media", s"$postId/thumb.webp")))),
      specVersion = 1
    )
    ProtoBytes.copyFrom(MediaMessages.toJson(m), UTF_8)

  /** A well-formed MediaProcessed missing metadata — its handler fails (`dimensionsOf` Left). */
  private def processedNoMetaJson(jobId: String, postId: String): ProtoBytes =
    val m = MediaProcessed(
      jobId = jobId,
      postId = postId,
      status = "ok",
      metadata = None,
      phash = "ph-x",
      specVersion = 1
    )
    ProtoBytes.copyFrom(MediaMessages.toJson(m), UTF_8)

  private def failedJson(jobId: String, postId: String, message: String): ProtoBytes =
    val m = MediaFailed(
      jobId = jobId,
      postId = postId,
      error = Some(JobError(code = "DECODE", message = message)),
      retriable = false
    )
    ProtoBytes.copyFrom(MediaMessages.toJson(m), UTF_8)

  private def consumerFor(broker: FakeBroker): HermesMediaResultConsumer =
    given ec: scala.concurrent.ExecutionContext = system.executionContext
    val handler = new MediaResultHandler(new InMemoryProcessedJobs, postFor)
    new HermesMediaResultConsumer(clientFor(broker), SubProcessed, SubFailed, handler)

  "HermesMediaResultConsumer.pollOnce" should {
    "drive a pending post to active from a MediaProcessed and ack the message" in {
      val broker = new FakeBroker(Map(SubProcessed -> TopicProcessed, SubFailed -> TopicFailed))
      createPending("hp1")
      val _ = broker.enqueue(TopicProcessed, processedJson("j-hp1", "hp1", 800, 600))
      val consumer = consumerFor(broker)

      consumer.pollOnce().futureValue shouldBe 1

      getState("hp1") match
        case PostState.Active(id, media, _) =>
          id shouldBe PostId.unsafe("hp1")
          media.dimensions.width shouldBe 800
          media.dimensions.height shouldBe 600
        case other => fail(s"expected Active, got $other")
      broker.messages(TopicProcessed) shouldBe empty
    }

    "drive a pending post to failed from a MediaFailed and ack the message" in {
      val broker = new FakeBroker(Map(SubProcessed -> TopicProcessed, SubFailed -> TopicFailed))
      createPending("hf1")
      val _ = broker.enqueue(TopicFailed, failedJson("j-hf1", "hf1", "unsupported codec"))
      val consumer = consumerFor(broker)

      consumer.pollOnce().futureValue shouldBe 1

      getState("hf1") shouldBe PostState.Failed(
        PostId.unsafe("hf1"),
        Md5("abc"),
        Filetype("image/png"),
        "unsupported codec"
      )
      broker.messages(TopicFailed) shouldBe empty
    }

    "leave a poison (malformed JSON) message un-acked and not corrupt any post" in {
      val broker = new FakeBroker(Map(SubProcessed -> TopicProcessed, SubFailed -> TopicFailed))
      createPending("hz1")
      val _ = broker.enqueue(TopicProcessed, ProtoBytes.copyFrom("{ not valid json", UTF_8))
      val consumer = consumerFor(broker)

      consumer.pollOnce().futureValue shouldBe 0 // did not throw; nothing handled

      broker.messages(TopicProcessed) should have size 1 // still queued for redelivery/DLQ
      getState("hz1") match
        case _: PostState.Pending => succeed
        case other => fail(s"expected Pending, got $other")
    }

    "leave a message un-acked when its handler fails, for redelivery" in {
      val broker = new FakeBroker(Map(SubProcessed -> TopicProcessed, SubFailed -> TopicFailed))
      createPending("hh1")
      // Missing metadata drives onProcessed to Future.failed → the `.recover` branch → not acked.
      val _ = broker.enqueue(TopicProcessed, processedNoMetaJson("j-hh1", "hh1"))
      val consumer = consumerFor(broker)

      consumer.pollOnce().futureValue shouldBe 0

      broker.messages(TopicProcessed) should have size 1 // still queued for retry
      getState("hh1") match
        case _: PostState.Pending => succeed
        case other => fail(s"expected Pending, got $other")
    }

    "ack only the successfully-handled message in a mixed batch, leaving the poison one queued" in {
      val broker = new FakeBroker(Map(SubProcessed -> TopicProcessed, SubFailed -> TopicFailed))
      createPending("hm1")
      val _ = broker.enqueue(TopicProcessed, processedJson("j-hm1", "hm1", 640, 480)) // good
      val poisonAck = broker.enqueue(TopicProcessed, ProtoBytes.copyFrom("{ nope", UTF_8)) // poison
      val consumer = consumerFor(broker)

      consumer
        .pollOnce()
        .futureValue shouldBe 1 // exactly the good message acked, batch not aborted

      getState("hm1") match
        case PostState.Active(_, media, _) => media.dimensions.width shouldBe 640
        case other => fail(s"expected Active, got $other")
      broker.messages(TopicProcessed).map(_.ackId) should contain only poisonAck
    }
  }

  /**
   * In-process stand-in for HermesMQ's PubSubService. `publish` appends the payload to an in-memory
   * per-topic queue (assigning a messageId that doubles as the ackId); `pull` returns up to `max`
   * queued messages for the subscription's bound topic; `ack` removes them. The admin/streaming
   * RPCs are unused by this spec and return benign defaults.
   */
  final private class FakeBroker(subscriptions: Map[String, String]) extends PubSubServicePowerApi:
    private val counter = new AtomicLong(0)
    private val queues = TrieMap.empty[String, TrieMap[String, PulledMessage]]

    private def queue(topic: String): TrieMap[String, PulledMessage] =
      queues.getOrElseUpdate(topic, TrieMap.empty)

    private def topicOf(subscriptionId: String): String =
      subscriptions.getOrElse(subscriptionId, subscriptionId)

    /** Seed a message onto a topic as the broker would after a publish. Returns the ackId. */
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
      Future.failed(new UnsupportedOperationException("not used by HermesMediaSpec"))

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
    ): Source[PulledMessage, NotUsed] = Source.empty

    override def consume(
        in: Source[ConsumeRequest, NotUsed],
        metadata: Metadata
    ): Source[PulledMessage, NotUsed] = Source.empty
