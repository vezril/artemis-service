package me.cference.artemis.persistence

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import me.cference.artemis.domain.SavedSearchesCommand.*
import me.cference.artemis.domain.{
  SavedSearch,
  SavedSearchesCommand,
  SavedSearchesState,
  SearchName
}
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
 * Integration test: saved-searches events round-trip through a real PostgreSQL journal
 * (testcontainers) — proving the CBOR serialization of
 * `SearchSaved`/`SearchRenamed`/`SearchRemoved` and the `SearchName` value type (mix-in +
 * serializer in `DomainJacksonModule`). A fresh entity replays the journal and recovers the exact
 * list, with sequence numbers in order.
 */
final class SavedSearchesPersistenceIT
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
  private val now = Instant.parse("2026-07-10T00:00:00Z")

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    testKit = ActorTestKit("artemis-saved-it", config())

  override def beforeStop(): Unit =
    if testKit != null then testKit.shutdownTestKit() // scalafix:ok DisableSyntax.null

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

  /** Spawn a fresh entity (replaying the journal), run a command, and stop it. */
  private def send(id: String, cmd: SavedSearchesCommand): StatusReply[Done] =
    val ref = testKit.spawn(SavedSearchesEntity(id))
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    ref ! SavedSearchesEntity.Execute(cmd, probe.ref)
    val reply = probe.receiveMessage()
    testKit.stop(ref)
    reply

  /** Spawn a FRESH entity (replaying the journal) and read its recovered list. */
  private def getState(id: String): SavedSearchesState =
    val ref = testKit.spawn(SavedSearchesEntity(id))
    val probe = testKit.createTestProbe[SavedSearchesState]()
    ref ! SavedSearchesEntity.Get(probe.ref)
    val state = probe.receiveMessage()
    testKit.stop(ref)
    state

  private def name(s: String) = SearchName.unsafe(s)

  "Saved-searches events round-trip through real Postgres" should {

    "persist save / rename / remove and recover the list after replay" in {
      val id = "list-1"
      send(id, SaveSearch(name("cats"), "cat_ears is:video", now)).isSuccess shouldBe true
      send(id, SaveSearch(name("dogs"), "dog_ears", now)).isSuccess shouldBe true
      send(id, RenameSearch(name("cats"), name("cat videos"), now)).isSuccess shouldBe true
      send(id, RemoveSearch(name("dogs"), now)).isSuccess shouldBe true
      seqNrs(s"saved-searches|$id") shouldBe Seq(1L, 2L, 3L, 4L)

      // A fresh entity replays (deserializing every event) — the list must recover exactly.
      getState(id).searches shouldBe Vector(SavedSearch(name("cat videos"), "cat_ears is:video"))
    }

    "recover uniqueness so a conflicting rename is rejected after replay" in {
      val id = "recov"
      send(id, SaveSearch(name("a"), "qa", now)).isSuccess shouldBe true
      send(id, SaveSearch(name("b"), "qb", now)).isSuccess shouldBe true

      // Fresh entity recovers {a, b}; renaming a → b collides and is rejected (no event persisted).
      val rejected = send(id, RenameSearch(name("a"), name("b"), now))
      rejected.isError shouldBe true
      rejected.getError.getMessage should include("already exists")
      seqNrs(s"saved-searches|$id") shouldBe Seq(1L, 2L)
    }
  }
