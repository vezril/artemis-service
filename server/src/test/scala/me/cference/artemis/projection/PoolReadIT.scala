package me.cference.artemis.projection

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import me.cference.artemis.config.PostgresConfig
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
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
 * Integration test (testcontainers) for the pool READ queries backing `GET /pools` and `GET
 * /pools/{id}/posts`: the row-value keyset ordering, the `DISTINCT ON` cover, the visible
 * (non-deleted) `postCount`, hydrated members in pool order, and composite `(position, post_id)`
 * keyset paging. Seeds the read model directly via the repo's public upserts (no
 * entity/projection), so it exercises the real SQL against a real Postgres.
 */
final class PoolReadIT
    extends AnyWordSpec
    with Matchers
    with ForAllTestContainer
    with ScalaFutures
    with BeforeAndAfterAll
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
  private var repo: ReadModelRepository = scala.compiletime.uninitialized
  private val now = Instant.parse("2026-07-08T00:00:00Z")

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    repo = new ReadModelRepository(pgConfig)

  private def pgConfig = PostgresConfig(
    container.host,
    container.mappedPort(5432),
    container.databaseName,
    container.username,
    container.password,
    5.seconds
  )

  override def beforeEach(): Unit =
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      Using.resource(conn.createStatement()) { st =>
        val _ = st.execute("TRUNCATE posts, pools, pool_posts")
      }
    }

  private def applySchema(): Unit =
    val sql = Using.resource(Source.fromResource("create_tables_postgres.sql"))(_.mkString)
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      Using.resource(conn.createStatement()) { st =>
        val _ = st.execute(sql)
      }
    }

  /** Seed one post row; `active` unless overridden, with a single thumbnail derivative. */
  private def seedPost(id: String, status: String = "active"): Unit =
    repo.upsertPostCreated(id, s"md5-$id", "png", status, now).futureValue
    repo
      .applyMediaProcessed(
        id,
        width = 800,
        height = 600,
        duration = None,
        phash = "0",
        derivativesJson = s"""[{"kind":"thumbnail","ref":"media/xx/$id-thumb.webp"}]""",
        status = status,
        specVersion = 1
      )
      .futureValue

  private def seedMember(poolId: String, postId: String): Unit =
    repo.addPoolPost(poolId, postId).futureValue

  "listPools" should {

    "order by name then id, count only visible members, and null the cover for an empty pool" in {
      seedPost("a1"); seedPost("a2")
      seedPost("del", status = "deleted")
      repo.upsertPool("p-bravo", "Bravo").futureValue
      repo.upsertPool("p-alpha", "Alpha").futureValue
      repo.upsertPool("p-empty", "Zempty").futureValue
      seedMember("p-alpha", "a1")
      seedMember("p-alpha", "a2")
      seedMember("p-alpha", "del") // soft-deleted → excluded from count and never a cover
      seedMember("p-bravo", "a2")

      val page = repo.listPools(after = None, limit = 10).futureValue
      val byId = page.map(r => r._1 -> (r._2, r._3)).toMap
      // Name order: Alpha, Bravo, Zempty
      page.map(_._1) shouldBe Seq("p-alpha", "p-bravo", "p-empty")
      byId("p-alpha") shouldBe ("Alpha", 2) // "del" not counted
      byId("p-bravo") shouldBe ("Bravo", 1)
      byId("p-empty") shouldBe ("Zempty", 0)

      val covers = repo.poolCovers(page.map(_._1)).futureValue
      covers.get("p-alpha").map(_.id) shouldBe Some("a1") // first visible member
      covers.get("p-bravo").map(_.id) shouldBe Some("a2")
      covers.get("p-empty") shouldBe None
      covers("p-alpha").derivatives should not be empty
      covers("p-alpha").md5 shouldBe Some("md5-a1")
    }

    "page via the row-value keyset with no overlap" in {
      // Three pools, page size 2. The repo returns `limit + 1` rows (the extra one lets the service
      // detect a next page); the page itself is the first `limit`, as the service trims it.
      repo.upsertPool("k-1", "Kilo1").futureValue
      repo.upsertPool("k-2", "Kilo2").futureValue
      repo.upsertPool("k-3", "Kilo3").futureValue
      val firstRaw = repo.listPools(after = None, limit = 2).futureValue
      firstRaw.size should be > 2 // limit + 1 → there IS a next page
      val firstPage = firstRaw.take(2)
      firstPage.map(_._1) shouldBe Seq("k-1", "k-2")
      val last = firstPage.last
      val second =
        repo.listPools(after = Some((last._2.toLowerCase, last._1)), limit = 2).futureValue
      second.map(_._1) shouldBe Seq("k-3")
    }
  }

  "poolPostsHydrated" should {

    "return hydrated members in pool order, excluding soft-deleted, paging each once" in {
      seedPost("m1"); seedPost("m2"); seedPost("m3")
      seedPost("mdel", status = "deleted")
      repo.upsertPool("mp", "Members").futureValue
      seedMember("mp", "m1")
      seedMember("mp", "mdel") // hidden
      seedMember("mp", "m2")
      seedMember("mp", "m3")

      val all = repo.poolPostsHydrated("mp", after = None, limit = 10).futureValue
      all.map(_._2.id) shouldBe Seq("m1", "m2", "m3") // mdel excluded, pool order preserved
      all.head._2.md5 shouldBe Some("md5-m1")
      all.head._2.derivatives should not be empty

      // Keyset paging over (position, post_id): page size 1, no drop/duplicate. The repo returns
      // `limit + 1`; the page is the first `limit` and the cursor is taken from the page's last row.
      val p1 = repo.poolPostsHydrated("mp", after = None, limit = 1).futureValue.take(1)
      p1.map(_._2.id) shouldBe Seq("m1")
      val (pos1, row1) = p1.last
      val p2 =
        repo.poolPostsHydrated("mp", after = Some((pos1, row1.id)), limit = 1).futureValue.take(1)
      p2.map(_._2.id) shouldBe Seq("m2")
      val (pos2, row2) = p2.last
      val p3 = repo.poolPostsHydrated("mp", after = Some((pos2, row2.id)), limit = 10).futureValue
      p3.map(_._2.id) shouldBe Seq("m3")
    }

    "return an empty page for an unknown pool" in {
      repo.poolPostsHydrated("does-not-exist", after = None, limit = 10).futureValue shouldBe empty
    }
  }
