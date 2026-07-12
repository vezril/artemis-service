package me.cference.artemis.similarity

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import me.cference.artemis.config.PostgresConfig
import me.cference.artemis.projection.ReadModelRepository
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * Integration test (testcontainers) for Tier-1 near-duplicate search through the read model
 * (similarity-search spec, tasks 2.2/2.3). Seeds posts with real phashes (via the projection's
 * `applyMediaProcessed`) and exercises `SimilarityService`: near-duplicates of a post (closest
 * first, self + far excluded) and reverse-image lookup from an arbitrary phash.
 */
final class SimilaritySearchIT
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
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(200, Millis))

  private given ec: ExecutionContext = ExecutionContext.global
  private var repo: ReadModelRepository = scala.compiletime.uninitialized
  private var service: SimilarityService = scala.compiletime.uninitialized

  private val zero = "0000000000000000" // target
  private val near2 = "0000000000000003" // distance 2
  private val near6 = "000000000000003f" // distance 6
  private val far40 = "ffffffffff000000" // distance 40

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    repo = new ReadModelRepository(pgConfig)
    service = new SimilarityService(repo)
    // A is the target (phash zero); B/C are near; D is far; P is pending (no phash).
    seed("A", zero)
    seed("B", near2)
    seed("C", near6)
    seed("D", far40)
    repo.upsertPostCreated("P", "md5", "image/png", "pending", now).futureValue

  private val now = Instant.parse("2026-07-10T00:00:00Z")

  private def pgConfig =
    PostgresConfig(
      container.host,
      container.mappedPort(5432),
      container.databaseName,
      container.username,
      container.password,
      5.seconds
    )

  /**
   * Create an active post carrying a perceptual hash (as the projection does on MediaProcessed).
   */
  private def seed(id: String, phash: String): Unit =
    repo.upsertPostCreated(id, "md5", "image/png", "pending", now).futureValue
    repo.applyMediaProcessed(id, 100, 100, None, phash, "[]", "active", 0).futureValue

  private def applySchema(): Unit =
    val sql = Using.resource(Source.fromResource("create_tables_postgres.sql"))(_.mkString)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      Using.resource(conn.createStatement()) { st =>
        val _ = st.execute(sql)
      }
    }

  "SimilarityService.similarTo" should {

    "return near-duplicates closest-first, excluding self and the far post" in {
      val result = service.similarTo("A", threshold = 10, limit = 10).futureValue
      result.map(_.id) shouldBe Seq("B", "C") // 2 bits, then 6 bits; D (40) and A itself excluded
      result.map(_.distance) shouldBe Seq(2, 6)
    }

    "return empty for a post with no phash (still pending)" in {
      service.similarTo("P", threshold = 64, limit = 10).futureValue shouldBe empty
    }

    "return empty when nothing is within a tight threshold" in {
      service.similarTo("A", threshold = 1, limit = 10).futureValue shouldBe empty
    }
  }

  "SimilarityService.reverseLookup" should {

    "find near matches for an arbitrary phash (nothing excluded)" in {
      // The zero phash matches A exactly (distance 0), then B and C; D is beyond threshold.
      val result = service.reverseLookup(zero, threshold = 10, limit = 10).futureValue
      result.map(_.id) shouldBe Seq("A", "B", "C")
      result.head shouldBe me.cference.artemis.domain.SimilarPost("A", 0)
    }

    "return empty for a novel phash with no near match" in {
      // All-ones is 64 bits from A/B/C and 24 from D — beyond a tight threshold, and not in the library.
      service
        .reverseLookup("ffffffffffffffff", threshold = 2, limit = 10)
        .futureValue shouldBe empty
    }
  }
