package me.cference.artemis.projection

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import me.cference.artemis.config.PostgresConfig
import me.cference.artemis.domain.*
import me.cference.artemis.domain.PoolCommand.*
import me.cference.artemis.persistence.PoolEntity
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.projection.ProjectionBehavior
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import java.time.Instant
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * Integration test (testcontainers): the pool projection folds `PoolEvent`s from the journal into
 * the `pools`/`pool_posts` read tables (read-model-projections spec 3.2 — ordered membership), and
 * the pool read model rebuilds identically by replaying from offset zero (3.3).
 */
final class PoolProjectionIT
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
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(200, Millis))

  private var testKit: ActorTestKit = scala.compiletime.uninitialized
  private var repo: ReadModelRepository = scala.compiletime.uninitialized

  private val now = Instant.parse("2026-07-08T00:00:00Z")

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    testKit = ActorTestKit("artemis-pool-proj", config())
    repo = new ReadModelRepository(pgConfig)(using testKit.system.executionContext)

  override def beforeStop(): Unit =
    if testKit != null then testKit.shutdownTestKit() // scalafix:ok DisableSyntax.null

  private def pgConfig = PostgresConfig(
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
        pekko.actor.provider = "local"
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
    val sql = Using.resource(Source.fromResource("create_tables_postgres.sql"))(_.mkString)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      Using.resource(conn.createStatement()) { st =>
        val _ = st.execute(sql)
      }
    }

  private def send(id: PoolId, cmd: PoolCommand): Unit =
    val ref = testKit.spawn(PoolEntity(id))
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    ref ! PoolEntity.Execute(cmd, probe.ref)
    probe.receiveMessage(10.seconds).isSuccess shouldBe true
    testKit.stop(ref)

  private def runProjection[A](body: => A): A =
    val p = testKit.spawn(ProjectionBehavior(PoolProjection(repo)(using testKit.system)))
    try body
    finally testKit.stop(p)

  private def truncateReadModel(): Unit =
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      Using.resource(conn.createStatement()) { st =>
        val _ = st.execute(
          "TRUNCATE posts, tags, tag_cooccurrence, pools, pool_posts, " +
            "projection_offset_store, projection_timestamp_offset_store, projection_management"
        )
      }
    }

  private def pid(s: String) = PostId.unsafe(s)

  "The pool projection" should {

    "fold a pool with ordered membership into pools/pool_posts (3.2)" in {
      val id = PoolId.unsafe("pool-1")
      send(id, CreatePool(id, PoolName.unsafe("Gallery"), now))
      send(id, AddPost(pid("pa"), now))
      send(id, AddPost(pid("pb"), now))
      send(id, AddPost(pid("pc"), now))

      runProjection {
        eventually {
          repo.getPool("pool-1").futureValue shouldBe Some(("pool-1", "Gallery"))
          repo.poolPosts("pool-1").futureValue shouldBe Seq(("pa", 0), ("pb", 1), ("pc", 2))
        }
      }
    }

    "rewrite positions on reorder, drop rows on remove, and update name on rename" in {
      val id = PoolId.unsafe("pool-2")
      send(id, CreatePool(id, PoolName.unsafe("Original"), now))
      send(id, AddPost(pid("pa"), now))
      send(id, AddPost(pid("pb"), now))
      send(id, AddPost(pid("pc"), now))

      runProjection {
        eventually {
          repo.poolPosts("pool-2").futureValue.map(_._1) shouldBe Seq("pa", "pb", "pc")
        }
      }

      send(id, Reorder(Vector(pid("pc"), pid("pa"), pid("pb")), now))
      runProjection {
        eventually {
          repo.poolPosts("pool-2").futureValue shouldBe Seq(("pc", 0), ("pa", 1), ("pb", 2))
        }
      }

      send(id, RemovePost(pid("pa"), now))
      send(id, RenamePool(PoolName.unsafe("Renamed"), now))
      runProjection {
        eventually {
          repo.poolPosts("pool-2").futureValue.map(_._1) shouldBe Seq("pc", "pb")
          repo.getPool("pool-2").futureValue.map(_._2) shouldBe Some("Renamed")
        }
      }
    }

    "remove the pool and its pool_posts on delete" in {
      val id = PoolId.unsafe("pool-3")
      send(id, CreatePool(id, PoolName.unsafe("Doomed"), now))
      send(id, AddPost(pid("px"), now))

      runProjection {
        eventually {
          repo.poolPosts("pool-3").futureValue should not be empty
        }
      }

      send(id, DeletePool(now))
      runProjection {
        eventually {
          repo.getPool("pool-3").futureValue shouldBe None
          repo.poolPosts("pool-3").futureValue shouldBe empty
        }
      }
    }

    "rebuild pool order identically by replaying from offset zero (3.3)" in {
      val id = PoolId.unsafe("pool-rb")
      send(id, CreatePool(id, PoolName.unsafe("Rebuildable"), now))
      send(id, AddPost(pid("r1"), now))
      send(id, AddPost(pid("r2"), now))
      send(id, Reorder(Vector(pid("r2"), pid("r1")), now))

      runProjection {
        eventually {
          repo.poolPosts("pool-rb").futureValue shouldBe Seq(("r2", 0), ("r1", 1))
        }
      }
      val before = repo.poolPosts("pool-rb").futureValue
      val nameBefore = repo.getPool("pool-rb").futureValue.map(_._2)

      truncateReadModel()
      repo.getPool("pool-rb").futureValue shouldBe None

      runProjection {
        eventually {
          repo.poolPosts("pool-rb").futureValue shouldBe before
          repo.getPool("pool-rb").futureValue.map(_._2) shouldBe nameBefore
        }
      }
    }
  }
