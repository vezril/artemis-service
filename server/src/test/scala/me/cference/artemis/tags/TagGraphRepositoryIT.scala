package me.cference.artemis.tags

import me.cference.artemis.config.PostgresConfig
import me.cference.artemis.domain.{Tag, TagCanonicalization}
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.testcontainers.utility.DockerImageName
import org.scalatest.BeforeAndAfterEach
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
 * Integration test (testcontainers): [[TagGraphRepository]] loads the direct alias/implication
 * edges from `tag_aliases`/`tag_implications` into a [[me.cference.artemis.domain.TagGraph]], and
 * the pure [[TagCanonicalization]] pipeline resolves aliases + expands implications transitively
 * over the loaded graph (tag-model spec: aliases, transitive implications). The repo stores/loads
 * only DIRECT edges; transitivity and cycle-safety are the pipeline's job, so this proves the
 * loaded shape feeds the pipeline correctly end-to-end.
 */
final class TagGraphRepositoryIT
    extends AnyWordSpec
    with Matchers
    with ForAllTestContainer
    with ScalaFutures
    with BeforeAndAfterEach:

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"),
    databaseName = "artemis",
    username = "artemis",
    password = "artemis"
  )

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(200, Millis))

  private given ExecutionContext = ExecutionContext.global

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()

  // One shared container across tests → truncate the relationship tables between each so rows from
  // one test can't bleed into another's loaded graph.
  override def beforeEach(): Unit =
    exec("TRUNCATE tag_aliases, tag_implications")

  private def pgConfig = PostgresConfig(
    container.host,
    container.mappedPort(5432),
    container.databaseName,
    container.username,
    container.password,
    5.seconds
  )

  private def repo = new TagGraphRepository(pgConfig)

  private def applySchema(): Unit =
    val sql = Using.resource(Source.fromResource("create_tables_postgres.sql"))(_.mkString)
    exec(sql)

  private def exec(sql: String): Unit = withConnection { st =>
    val _ = st.execute(sql)
  }

  private def withConnection(use: java.sql.Statement => Unit): Unit =
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      Using.resource(conn.createStatement())(st => use(st))
    }

  private def alias(antecedent: String, consequent: String): Unit =
    exec(s"INSERT INTO tag_aliases (antecedent, consequent) VALUES ('$antecedent', '$consequent')")

  private def implies(antecedent: String, consequent: String): Unit =
    exec(
      s"INSERT INTO tag_implications (antecedent, consequent) VALUES ('$antecedent', '$consequent')"
    )

  "TagGraphRepository.loadGraph" should {

    "load aliases and implications so canonicalization resolves + expands transitively" in {
      alias("catgirl", "cat_girl")
      implies("cat_girl", "animal_ears")
      implies("animal_ears", "animal_humanoid")

      val graph = repo.loadGraph().futureValue
      TagCanonicalization.canonicalize(Set(Tag.unsafe("catgirl")), graph) shouldBe
        Set(Tag.unsafe("cat_girl"), Tag.unsafe("animal_ears"), Tag.unsafe("animal_humanoid"))
    }

    "resolve an alias chain a -> b -> c to the terminal consequent c" in {
      alias("a", "b")
      alias("b", "c")

      val graph = repo.loadGraph().futureValue
      TagCanonicalization.canonicalize(Set(Tag.unsafe("a")), graph) shouldBe Set(Tag.unsafe("c"))
    }

    "terminate on an implication cycle loaded from the DB" in {
      implies("x", "y")
      implies("y", "x")

      val graph = repo.loadGraph().futureValue
      TagCanonicalization.canonicalize(Set(Tag.unsafe("x")), graph) shouldBe
        Set(Tag.unsafe("x"), Tag.unsafe("y"))
    }
  }
