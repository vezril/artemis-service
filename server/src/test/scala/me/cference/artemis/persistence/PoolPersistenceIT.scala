package me.cference.artemis.persistence

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import me.cference.artemis.domain.*
import me.cference.artemis.domain.PoolCommand.*
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
 * Integration test: pool-aggregate events round-trip through a real PostgreSQL journal
 * (testcontainers) with correct sequence numbers, and ordered membership recovers after an entity
 * restart (event-persistence spec, OpenSpec task 1.5).
 */
final class PoolPersistenceIT
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

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    testKit = ActorTestKit("artemis-pool-it", config())

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

  private def send(id: PoolId, cmd: PoolCommand): StatusReply[Done] =
    val ref = testKit.spawn(PoolEntity(id))
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    ref ! PoolEntity.Execute(cmd, probe.ref)
    val reply = probe.receiveMessage()
    testKit.stop(ref)
    reply

  /** Spawn a FRESH entity (replaying the journal) and read its recovered state. */
  private def getState(id: PoolId): PoolState =
    val ref = testKit.spawn(PoolEntity(id))
    val probe = testKit.createTestProbe[PoolState]()
    ref ! PoolEntity.Get(probe.ref)
    val state = probe.receiveMessage()
    testKit.stop(ref)
    state

  private val pa = PostId.unsafe("a")
  private val pb = PostId.unsafe("b")
  private val pc = PostId.unsafe("c")

  "Pool events round-trip through real Postgres" should {
    "persist create, adds, rename and reorder with sequence numbers in order" in {
      val id = PoolId.unsafe("1")
      send(id, CreatePool(id, PoolName.unsafe("summer"), now)).isSuccess shouldBe true
      send(id, AddPost(pa, now)).isSuccess shouldBe true
      send(id, AddPost(pb, now)).isSuccess shouldBe true
      send(id, AddPost(pc, now)).isSuccess shouldBe true
      send(id, RenamePool(PoolName.unsafe("summer 2026"), now)).isSuccess shouldBe true
      send(id, Reorder(Vector(pc, pa, pb), now)).isSuccess shouldBe true
      seqNrs("pool|1") shouldBe Seq(1L, 2L, 3L, 4L, 5L, 6L)

      // A fresh entity replays the journal; the reordered membership order must survive replay.
      getState(id) match
        case PoolState.Active(_, name, posts) =>
          posts shouldBe Vector(pc, pa, pb)
          name shouldBe PoolName.unsafe("summer 2026")
        case other => fail(s"expected recovered Active pool, got $other")
    }
  }

  "Pool crash recovery" should {
    "recover ordered membership so a duplicate add is a no-op and remove-absent is rejected" in {
      val id = PoolId.unsafe("recov")
      send(id, CreatePool(id, PoolName.unsafe("recov"), now)).isSuccess shouldBe true
      send(id, AddPost(pa, now)).isSuccess shouldBe true
      send(id, AddPost(pb, now)).isSuccess shouldBe true

      // Fresh entity recovers [a, b]; re-adding a is an accepted no-op (no event persisted).
      send(id, AddPost(pa, now)).isSuccess shouldBe true
      // Removing a post never added is rejected.
      val rejected = send(id, RemovePost(pc, now))
      rejected.isError shouldBe true
      rejected.getError.getMessage should include("not a member")

      seqNrs("pool|recov") shouldBe Seq(1L, 2L, 3L)
    }

    "keep a deleted pool deleted after replay" in {
      val id = PoolId.unsafe("goner")
      send(id, CreatePool(id, PoolName.unsafe("goner"), now)).isSuccess shouldBe true
      send(id, DeletePool(now)).isSuccess shouldBe true
      val reply = send(id, AddPost(pa, now))
      reply.isError shouldBe true
      reply.getError.getMessage should include("does not exist")
      seqNrs("pool|goner") shouldBe Seq(1L, 2L)
    }
  }
