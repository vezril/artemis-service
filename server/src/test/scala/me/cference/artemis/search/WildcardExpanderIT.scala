package me.cference.artemis.search

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import me.cference.artemis.config.PostgresConfig
import me.cference.artemis.domain.Tag
import me.cference.artemis.projection.ReadModelRepository
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.io.Source
import scala.util.Using

/**
 * Integration test (testcontainers) for the concrete wildcard expander (search-dsl "Wildcard tag
 * expansion is bounded", tasks 3.2): a `*` pattern LIKE-matches `tags.name`, ordered by `post_count
 * DESC`, capped at `cap` (over-cap ⇒ `WildcardTooBroad`), returning the concrete tags.
 */
final class WildcardExpanderIT
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
    seedTags()
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

  private def seedTags(): Unit =
    // Distinct post_counts so the capped top-N ordering is deterministic.
    withJdbc { st =>
      val _ = st.execute(
        """INSERT INTO tags (name, category, post_count) VALUES
          |  ('cat_ears', 0, 30),
          |  ('cat_girl', 0, 20),
          |  ('cat_tail', 0, 10),
          |  ('dog_ears', 0, 99),
          |  ('cat_5%off', 0, 5)""".stripMargin
      )
    }

  private def withJdbc(use: java.sql.Statement => Unit): Unit =
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    )(conn => Using.resource(conn.createStatement())(use))

  private def tag(s: String): Tag = Tag.from(s).toOption.getOrElse(sys.error(s"bad tag: $s"))

  "ReadModelWildcardExpander.expand" should {

    "expand a wildcard to all matching tags when under the cap" in {
      val expander = new ReadModelWildcardExpander(repo, cap = 10)
      expander.expand("cat_*").futureValue shouldBe
        Right(Set(tag("cat_ears"), tag("cat_girl"), tag("cat_tail"), tag("cat_5%off")))
    }

    "reject an over-broad wildcard that exceeds the cap" in {
      val expander = new ReadModelWildcardExpander(repo, cap = 2)
      expander.expand("cat_*").futureValue shouldBe Left(WildcardTooBroad("cat_*"))
    }

    "accept exactly at the cap boundary but reject one match beyond it" in {
      // 4 tags start with cat_ (ears 30, girl 20, tail 10, 5%off 5); dog_ears is excluded.
      new ReadModelWildcardExpander(repo, cap = 4).expand("cat_*").futureValue.map(_.size) shouldBe
        Right(4)
      new ReadModelWildcardExpander(repo, cap = 3).expand("cat_*").futureValue shouldBe
        Left(WildcardTooBroad("cat_*"))
    }

    "order matches by descending post_count via a bounded fetch" in {
      // Observable ordering lives on the repo lookup (the port returns an unordered Set).
      // Within cat_*: ears(30) > girl(20) > tail(10) > 5%off(5); dog_ears(99) is out of prefix.
      repo.matchTagNames("cat\\_%", 10).futureValue shouldBe
        Seq("cat_ears", "cat_girl", "cat_tail", "cat_5%off")
    }

    "treat a literal % in the pattern as a literal, not a wildcard" in {
      val expander = new ReadModelWildcardExpander(repo, cap = 10)
      // `cat_5%*` must match only `cat_5%off` (the % is escaped), not every cat_ tag.
      expander.expand("cat_5%*").futureValue shouldBe Right(Set(tag("cat_5%off")))
    }
  }
