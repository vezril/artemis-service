package me.cference.artemis.projection

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import me.cference.artemis.config.PostgresConfig
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
 * Integration test (testcontainers) for the related-tags co-occurrence projection + cosine query
 * (related-tags spec). Seeds through the projection's own maintenance methods (`upsertPostCreated`
 * + `setTags` + `deletePost`) so the `tag_cooccurrence` table is built exactly as the runtime
 * projection builds it. Covers: incremental maintenance on create/tags-change/delete (1.1),
 * idempotency (1.2), and cosine ranking where a correlated tag outranks a ubiquitous one (2.1).
 */
final class RelatedTagsIT
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
    repo = new ReadModelRepository(pgConfig)

  private def pgConfig =
    PostgresConfig(
      container.host,
      container.mappedPort(5432),
      container.databaseName,
      container.username,
      container.password,
      5.seconds
    )

  private def applySchema(): Unit =
    val sql = Using.resource(Source.fromResource("create_tables_postgres.sql"))(_.mkString)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      Using.resource(conn.createStatement()) { st =>
        val _ = st.execute(sql)
      }
    }

  /**
   * Create a post then set its tags — exactly what the projection does for PostCreated+TagsChanged.
   */
  private def tagPost(id: String, tags: Seq[String]): Unit =
    repo
      .upsertPostCreated(id, "md5", "image/png", "active", Instant.parse("2026-07-10T00:00:00Z"))
      .futureValue
    repo.setTags(id, tags).futureValue

  /** Read a co-occurrence count directly (canonical pair order). */
  private def coCount(a: String, b: String): Int =
    val (x, y) = if a < b then (a, b) else (b, a)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      val rs = conn
        .createStatement()
        .executeQuery(s"SELECT count FROM tag_cooccurrence WHERE tag_a = '$x' AND tag_b = '$y'")
      if rs.next() then rs.getInt("count") else 0
    }

  "The co-occurrence projection" should {

    "increment the pairs of a newly-tagged post" in {
      tagPost("c1", Seq("nc_a", "nc_b", "nc_c"))
      coCount("nc_a", "nc_b") shouldBe 1
      coCount("nc_a", "nc_c") shouldBe 1
      coCount("nc_b", "nc_c") shouldBe 1
    }

    "adjust only the diff on a tag change" in {
      tagPost("d1", Seq("dx_a", "dx_b"))
      coCount("dx_a", "dx_b") shouldBe 1
      // {a,b} -> {a,c}: (a,b) drops, (a,c) appears, nothing else touched.
      repo.setTags("d1", Seq("dx_a", "dx_c")).futureValue
      coCount("dx_a", "dx_b") shouldBe 0
      coCount("dx_a", "dx_c") shouldBe 1
    }

    "be idempotent — re-applying the same tags is a zero-delta no-op" in {
      tagPost("i1", Seq("id_a", "id_b"))
      repo.setTags("i1", Seq("id_a", "id_b")).futureValue // same again
      repo.setTags("i1", Seq("id_a", "id_b")).futureValue
      coCount("id_a", "id_b") shouldBe 1
    }

    "decrement a post's contribution on delete" in {
      tagPost("del1", Seq("del_x", "del_y"))
      coCount("del_x", "del_y") shouldBe 1
      repo.deletePost("del1").futureValue
      coCount("del_x", "del_y") shouldBe 0
    }
  }

  "Cosine-ranked related tags" should {

    "rank a correlated tag above a ubiquitous one" in {
      // cat_ears co-occurs with cat_girl on all 3 of its posts, and with 1girl (which is on 10 posts).
      List("p1", "p2", "p3").foreach(id => tagPost(id, Seq("cat_ears", "cat_girl", "1girl")))
      (4 to 10).foreach(i => tagPost(s"p$i", Seq("1girl")))

      val related = repo.relatedTags("cat_ears", 10).futureValue
      related.map(_.name) should not contain "cat_ears" // self excluded
      related.map(_.name).take(2) shouldBe Seq("cat_girl", "1girl")
      // cosine(cat_girl) = 3/sqrt(3·3) = 1.0 > cosine(1girl) = 3/sqrt(3·10) ≈ 0.55
      val byName = related.map(r => r.name -> r.cosine).toMap
      byName("cat_girl") should be > byName("1girl")
    }

    "return empty for a tag with no co-occurrences" in {
      tagPost("lonely-post", Seq("lonely"))
      repo.relatedTags("lonely", 10).futureValue shouldBe empty
    }
  }
