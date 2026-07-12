package me.cference.artemis.persistence

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import me.cference.artemis.domain.*
import me.cference.artemis.domain.PostCommand.*
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.pattern.StatusReply
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import java.time.Instant
import scala.io.Source
import scala.util.Using

/**
 * Integration test: post-aggregate events round-trip through a real PostgreSQL journal
 * (testcontainers) with correct sequence numbers, and state recovers after an entity restart
 * (event-persistence spec, OpenSpec task 1.5). The round-trip exercises `DomainJacksonModule` for
 * every persisted event kind, so a missing (de)serializer fails RED here.
 */
final class PostPersistenceIT
    extends AnyWordSpec
    with Matchers
    with ForAllTestContainer
    with ScalaFutures
    with BeforeAndAfterAll:

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"),
    databaseName = "artemis",
    username = "artemis",
    password = "artemis"
  )

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(100, Millis))

  private var testKit: ActorTestKit = scala.compiletime.uninitialized

  private val now = Instant.parse("2026-07-08T00:00:00Z")
  private val md5 = Md5("d41d8cd98f00b204e9800998ecf8427e")
  private val filetype = Filetype("image/png")
  private val phash = Phash("f00dcafe")
  private val dimensions = Dimensions.unsafe(1920, 1080, Some(0L))
  private val derivatives =
    Vector(Derivative("thumbnail", "blob://thumb"), Derivative("sample", "blob://sample"))

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    testKit = ActorTestKit("artemis-post-it", config())

  override def beforeStop(): Unit =
    if testKit != null then testKit.shutdownTestKit() // scalafix:ok DisableSyntax.null

  private def config(): Config =
    ConfigFactory
      .parseString(s"""
        pekko.actor.provider = "local"
        # Don't let the shared runtime config's coordinated-shutdown kill the forked test JVM
        # when the testkit terminates — it races the sbt fork harness (spurious EOFException).
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
    // The repo-root `ddl/` directory is on the test classpath (build.sbt adds it as an unmanaged
    // Test resource dir), so the schema is a classpath-root resource.
    val sql = Using.resource(Source.fromResource("create_tables_postgres.sql"))(_.mkString)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      Using.resource(conn.createStatement()) { st =>
        val _ = st.execute(sql)
      }
    }

  private def seqNrs(persistenceId: String): Seq[Long] =
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      val rs = conn
        .createStatement()
        .executeQuery(
          s"SELECT seq_nr FROM event_journal WHERE persistence_id = '$persistenceId' ORDER BY seq_nr"
        )
      Iterator.continually(rs).takeWhile(_.next()).map(_.getLong("seq_nr")).toList
    }

  private def send(id: PostId, cmd: PostCommand): StatusReply[Done] =
    val ref = testKit.spawn(PostEntity(id))
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    ref ! PostEntity.Execute(cmd, probe.ref)
    val reply = probe.receiveMessage()
    testKit.stop(ref)
    reply

  /** Spawn a FRESH entity (replaying the journal) and read its recovered state. */
  private def getState(id: PostId): PostState =
    val ref = testKit.spawn(PostEntity(id))
    val probe = testKit.createTestProbe[PostState]()
    ref ! PostEntity.Get(probe.ref)
    val state = probe.receiveMessage()
    testKit.stop(ref)
    state

  private def create(id: PostId): StatusReply[Done] =
    send(id, CreatePost(id, md5, filetype, now))

  private def process(id: PostId): StatusReply[Done] =
    send(id, RecordProcessed(dimensions, derivatives, phash, now))

  "Post events round-trip through real Postgres" should {
    "persist create, process, tag-change and favorite with sequence numbers 1..4" in {
      val id = PostId.unsafe("1")
      create(id).isSuccess shouldBe true
      process(id).isSuccess shouldBe true
      send(id, ChangeTags(Set(Tag.unsafe("landscape"), Tag.unsafe("sky")), now)).isSuccess shouldBe
        true
      send(id, Favorite(now)).isSuccess shouldBe true
      seqNrs("post|1") shouldBe Seq(1L, 2L, 3L, 4L)
    }

    "persist rating, parent, score, source and unfavorite events" in {
      val id = PostId.unsafe("2")
      create(id).isSuccess shouldBe true
      process(id).isSuccess shouldBe true
      send(id, SetRating("e", now)).isSuccess shouldBe true
      send(id, SetParent(PostId.unsafe("99"), now)).isSuccess shouldBe true
      send(id, Score(5, now)).isSuccess shouldBe true
      send(id, SetSource("https://example.com/art", now)).isSuccess shouldBe true
      send(id, Favorite(now)).isSuccess shouldBe true
      send(id, Unfavorite(now)).isSuccess shouldBe true
      // create, process, rating, parent, score, source, favorite, unfavorite = 8 events.
      seqNrs("post|2") shouldBe Seq(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)
    }
  }

  "Post crash recovery" should {
    "keep a deleted post deleted after replay" in {
      val id = PostId.unsafe("goner")
      create(id).isSuccess shouldBe true
      process(id).isSuccess shouldBe true
      send(id, Delete(now)).isSuccess shouldBe true

      // Fresh entity instance recovers from the journal; a non-Restore command is rejected.
      val reply = send(id, Favorite(now))
      reply.isError shouldBe true
      reply.getError.getMessage should include("does not exist")
      seqNrs("post|goner") shouldBe Seq(1L, 2L, 3L)
    }

    "recover the last canonical tag set after replay" in {
      val id = PostId.unsafe("tagged")
      val finalTags = Set(Tag.unsafe("cat_girl"), Tag.unsafe("animal_ears"))
      create(id).isSuccess shouldBe true
      process(id).isSuccess shouldBe true
      send(id, ChangeTags(Set(Tag.unsafe("first")), now)).isSuccess shouldBe true
      send(id, ChangeTags(finalTags, now)).isSuccess shouldBe true

      // A fresh entity replays the journal; the recovered state must reflect the LAST canonical
      // tag set (not the earlier one), proving tag-edit history folds correctly on replay.
      getState(id) match
        case PostState.Active(_, _, content) => content.tags shouldBe finalTags
        case other => fail(s"expected recovered Active post, got $other")

      seqNrs("post|tagged") shouldBe Seq(1L, 2L, 3L, 4L)
    }

    "restore a deleted post after replay so it accepts edits again" in {
      val id = PostId.unsafe("revived")
      create(id).isSuccess shouldBe true
      process(id).isSuccess shouldBe true
      send(id, Delete(now)).isSuccess shouldBe true
      // Persists a real PostRestored (Deleted -> Active), unlike restoring a live post (a no-op).
      send(id, Restore(now)).isSuccess shouldBe true

      // A fresh entity replays Deleted -> Restored -> Active; a live-only command now succeeds.
      send(id, Favorite(now)).isSuccess shouldBe true
      getState(id) match
        case PostState.Active(_, _, content) => content.favorited shouldBe true
        case other => fail(s"expected recovered Active post, got $other")

      seqNrs("post|revived") shouldBe Seq(1L, 2L, 3L, 4L, 5L)
    }

    "reprocess after recovery, refreshing media facts" in {
      val id = PostId.unsafe("reproc")
      create(id).isSuccess shouldBe true
      process(id).isSuccess shouldBe true
      // Fresh entity recovers to Active and accepts another RecordProcessed.
      send(
        id,
        RecordProcessed(Dimensions.unsafe(640, 480, None), Vector.empty, phash, now)
      ).isSuccess shouldBe true
      seqNrs("post|reproc") shouldBe Seq(1L, 2L, 3L)
    }
  }
