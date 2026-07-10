package me.cference.artemis.search

import me.cference.artemis.config.PostgresConfig
import me.cference.artemis.projection.{ReadModelRepository, TagSuggestion}
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
 * Integration test (testcontainers) for tag-name autocomplete (catalog-api "autocomplete (trigram,
 * grammar-aware)", task 5.3): prefix-match `tags.name`, rank by `post_count DESC`, and carry the
 * alias consequent (LEFT JOIN `tag_aliases`) so the UI can hint "→ canonical".
 */
final class AutocompleteIT
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

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    seed()
    repo = new ReadModelRepository(pgConfig)

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
   * Four `cat`-prefixed tags with distinct post_counts (so the ranking is deterministic) plus an
   * off-prefix `dog_ears`. `catgirl` is also an alias antecedent (→ `cat_girl`) so its suggestion
   * carries `alias_of`.
   */
  private def seed(): Unit =
    withJdbc { st =>
      val _ = st.execute(
        """INSERT INTO tags (name, category, post_count) VALUES
          |  ('cat_ears', 0, 30),
          |  ('cat_girl', 4, 20),
          |  ('cat_tail', 0, 10),
          |  ('catgirl', 0, 5),
          |  ('dog_ears', 0, 99)""".stripMargin
      )
      val _ = st.execute(
        "INSERT INTO tag_aliases (antecedent, consequent) VALUES ('catgirl', 'cat_girl')"
      )
    }

  private def withJdbc(use: java.sql.Statement => Unit): Unit =
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    )(conn => Using.resource(conn.createStatement())(use))

  private def suggest(q: String, limit: Int = 10): Seq[TagSuggestion] =
    repo.autocompleteTags(q, limit).futureValue

  "ReadModelRepository.autocompleteTags" should {

    "prefix-match the query and rank by descending post_count (off-prefix tags excluded)" in {
      suggest("cat").map(_.name) shouldBe Seq("cat_ears", "cat_girl", "cat_tail", "catgirl")
    }

    "carry the alias consequent for an alias antecedent, and None otherwise" in {
      val byName = suggest("cat").map(s => s.name -> s.aliasOf).toMap
      byName("catgirl") shouldBe Some("cat_girl")
      byName("cat_ears") shouldBe None
    }

    "carry each suggestion's category and post_count" in {
      val girl = suggest("cat").find(_.name == "cat_girl").get
      girl.category shouldBe 4
      girl.postCount shouldBe 20
    }

    "respect the limit, keeping the top-N by post_count" in {
      suggest("cat", limit = 2).map(_.name) shouldBe Seq("cat_ears", "cat_girl")
    }

    "escape LIKE metacharacters in the query so they match literally" in {
      // A `%` in the query must not act as a wildcard: `cat%` matches nothing (no such literal tag).
      suggest("cat%") shouldBe empty
    }
  }
