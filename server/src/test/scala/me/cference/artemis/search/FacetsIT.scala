package me.cference.artemis.search

import me.cference.artemis.config.PostgresConfig
import me.cference.artemis.domain.Tag
import me.cference.artemis.projection.{FacetEntry, ReadModelRepository}
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.testcontainers.utility.DockerImageName
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.sql.DriverManager
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * Integration test (testcontainers) for the tag-facet aggregation (catalog-api "Tag facets for a
 * query", task 5.2): over the SAME matching set as the search (so soft-deleted rows are excluded),
 * the tags occurring across the matches, each with its category and a per-tag count of how many
 * matches carry it. Reuses `SqlCompiler.compileWhere` for the plan→WHERE.
 */
final class FacetsIT
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
  private var facetExecutor: FacetExecutor = scala.compiletime.uninitialized

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    seed()
    repo = new ReadModelRepository(pgConfig)
    facetExecutor = new FacetExecutor(repo)

  private def pgConfig = PostgresConfig(
    container.host,
    container.mappedPort(5432),
    container.databaseName,
    container.username,
    container.password,
    5.seconds
  )

  private def applySchema(): Unit =
    val sql = Using.resource(Source.fromResource("create_tables_postgres.sql"))(_.mkString)
    withJdbc { st =>
      val _ = st.execute(sql)
    }

  /**
   * p1..p3 carry `1girl` (the query anchor); p4 is off-topic; p5 carries `1girl` but is
   * soft-deleted so it must not contribute to any facet count. `dog_ears` is deliberately absent
   * from the `tags` table so its facet category falls back to 0 via COALESCE.
   */
  private def seed(): Unit =
    seedPost("p1", Seq("1girl", "cat_ears"), "active")
    seedPost("p2", Seq("1girl", "cat_ears", "monochrome"), "active")
    seedPost("p3", Seq("1girl", "dog_ears"), "active")
    seedPost("p4", Seq("landscape"), "active")
    seedPost("p5", Seq("1girl", "cat_ears"), "deleted")
    withJdbc { st =>
      val _ = st.execute(
        """INSERT INTO tags (name, category, post_count) VALUES
          |  ('1girl', 0, 4),
          |  ('cat_ears', 0, 3),
          |  ('monochrome', 5, 1)""".stripMargin
      )
    }

  private def seedPost(id: String, tags: Seq[String], status: String): Unit =
    val arr = tags.map(t => s"'$t'").mkString("ARRAY[", ",", "]::text[]")
    withJdbc { st =>
      val _ = st.execute(
        s"""INSERT INTO posts (id, tags, score, fav_count, status, created_at)
           |VALUES ('$id', $arr, 0, 0, '$status', '2026-01-01T00:00:00Z'::timestamptz)""".stripMargin
      )
    }

  private def withJdbc(use: java.sql.Statement => Unit): Unit =
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    )(conn => Using.resource(conn.createStatement())(use))

  private def plan(includes: Set[String]): QueryPlan =
    QueryPlan(includes.map(Tag.unsafe), Set.empty, Nil, Nil, None, None)

  private def facetsOf(p: QueryPlan): Seq[FacetEntry] =
    facetExecutor.facets(p).futureValue match
      case Right(entries) => entries
      case Left(err) => fail(s"facets failed: ${err.message}")

  "FacetExecutor.facets" should {

    "count each tag across the matching posts, excluding soft-deleted rows" in {
      val entries = facetsOf(plan(Set("1girl")))
      val counts = entries.map(e => e.name -> e.count).toMap
      counts shouldBe Map("1girl" -> 3, "cat_ears" -> 2, "dog_ears" -> 1, "monochrome" -> 1)
    }

    "carry each tag's category, defaulting a tag missing from the tags table to 0" in {
      val entries = facetsOf(plan(Set("1girl")))
      val category = entries.map(e => e.name -> e.category).toMap
      category("monochrome") shouldBe 5
      category("dog_ears") shouldBe 0 // absent from `tags` → COALESCE to 0
      category("1girl") shouldBe 0
    }

    "order facets by descending count with a name tiebreak" in {
      facetsOf(plan(Set("1girl"))).map(_.name) shouldBe
        Seq("1girl", "cat_ears", "dog_ears", "monochrome")
    }
  }
