package me.cference.artemis.search

import me.cference.artemis.config.PostgresConfig
import me.cference.artemis.domain.Tag
import me.cference.artemis.projection.ReadModelRepository
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
 * Integration test (testcontainers) for the search-dsl execution path (task 4.4): compile a merged
 * [[QueryPlan]] to ONE parameterized SQL query and run it against seeded `posts` rows via
 * [[SearchExecutor]]. Covers positional AND, negation, a flat OR group, metatag filters, keyset
 * paging by `order:score` (no overlap/gap; union == a single full query), stable `order:random`
 * across pages (no repeat/skip), and default exclusion of soft-deleted rows.
 */
final class SearchQueryIT
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
  private var executor: SearchExecutor = scala.compiletime.uninitialized

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    seedPosts()
    repo = new ReadModelRepository(pgConfig)
    executor = new SearchExecutor(repo)

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
   * Seven posts with varied tags/rating/score/duration/status/created_at. `p6` is soft-deleted
   * (must be excluded by default); `p4` is the only video (duration set).
   */
  private def seedPosts(): Unit =
    seed("p1", Seq("1girl", "cat_ears"), "s", 5, None, "active", "2026-01-01T00:00:00Z")
    seed(
      "p2",
      Seq("1girl", "cat_ears", "monochrome"),
      "s",
      50,
      None,
      "active",
      "2026-01-02T00:00:00Z"
    )
    seed("p3", Seq("1girl", "dog_ears"), "q", 20, None, "active", "2026-01-03T00:00:00Z")
    seed("p4", Seq("1girl", "cat_ears"), "e", 100, Some(60L), "active", "2026-01-04T00:00:00Z")
    seed("p5", Seq("cat_ears"), "s", 3, None, "active", "2026-01-05T00:00:00Z")
    seed("p6", Seq("1girl", "cat_ears"), "s", 999, None, "deleted", "2026-01-06T00:00:00Z")
    seed("p7", Seq("landscape"), "g", 15, None, "active", "2026-01-07T00:00:00Z")

  private def seed(
      id: String,
      tags: Seq[String],
      rating: String,
      score: Int,
      duration: Option[Long],
      status: String,
      createdAt: String
  ): Unit =
    val arr = tags.map(t => s"'$t'").mkString("ARRAY[", ",", "]::text[]")
    val dur = duration.map(_.toString).getOrElse("NULL")
    withJdbc { st =>
      val _ = st.execute(
        s"""INSERT INTO posts
           |  (id, tags, rating, score, fav_count, width, height, duration, filetype, md5, status, created_at)
           |VALUES
           |  ('$id', $arr, '$rating', $score, 0, 100, 100, $dur, 'image/png',
           |   'd41d8cd98f00b204e9800998ecf8427e', '$status', '$createdAt'::timestamptz)""".stripMargin
      )
    }

  private def withJdbc(use: java.sql.Statement => Unit): Unit =
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    )(conn => Using.resource(conn.createStatement())(use))

  // --- helpers ------------------------------------------------------------

  private def tagSet(ts: String*): Set[Tag] = ts.map(Tag.unsafe).toSet

  private def plan(
      includes: Set[Tag] = Set.empty,
      excludes: Set[Tag] = Set.empty,
      orGroups: Seq[Set[Tag]] = Nil,
      predicates: Seq[MetaTerm] = Nil,
      order: Option[String] = None,
      limit: Option[Int] = None
  ): QueryPlan = QueryPlan(includes, excludes, orGroups, predicates, order, limit)

  private def run(p: QueryPlan, cursor: Option[String] = None, pageSize: Int = 100): SearchPage =
    executor.search(p, cursor, pageSize).futureValue match
      case Right(page) => page
      case Left(err) => fail(s"compile failed: ${err.message}")

  private def idsOf(p: QueryPlan, pageSize: Int = 100): Set[String] =
    run(p, pageSize = pageSize).rows.map(_.id).toSet

  // --- tests --------------------------------------------------------------

  "SearchExecutor" should {

    "match positional AND (1girl cat_ears), excluding deleted by default" in {
      idsOf(plan(includes = tagSet("1girl", "cat_ears"))) shouldBe Set("p1", "p2", "p4")
    }

    "apply negation (1girl -monochrome)" in {
      idsOf(plan(includes = tagSet("1girl"), excludes = tagSet("monochrome"))) shouldBe
        Set("p1", "p3", "p4")
    }

    "apply a flat OR group ((cat_ears OR dog_ears) AND 1girl)" in {
      idsOf(
        plan(includes = tagSet("1girl"), orGroups = Seq(tagSet("cat_ears", "dog_ears")))
      ) shouldBe
        Set("p1", "p2", "p3", "p4")
    }

    "apply a numeric metatag filter (score:>10)" in {
      idsOf(
        plan(
          includes = tagSet("1girl"),
          predicates = Seq(MetaTerm(Polarity.Include, "score", MetaValue.Compare(Cmp.Gt, "10")))
        )
      ) shouldBe
        Set("p2", "p3", "p4")
    }

    "apply an enum metatag filter (rating:s)" in {
      idsOf(
        plan(
          includes = tagSet("1girl"),
          predicates = Seq(MetaTerm(Polarity.Include, "rating", MetaValue.Scalar("s")))
        )
      ) shouldBe
        Set("p1", "p2")
    }

    "filter video by duration range (is:video duration:30..120)" in {
      idsOf(
        plan(
          includes = tagSet("1girl"),
          predicates = Seq(
            MetaTerm(Polarity.Include, "is", MetaValue.Scalar("video")),
            MetaTerm(Polarity.Include, "duration", MetaValue.RangeVal(Some("30"), Some("120")))
          )
        )
      ) shouldBe Set("p4")
    }

    "exclude soft-deleted rows by default (p6 never appears)" in {
      idsOf(plan(includes = tagSet("1girl", "cat_ears"))) should not contain "p6"
    }

    "page by order:score with keyset — no overlap, no gap, union == a single full query" in {
      val p = plan(includes = tagSet("1girl"), order = Some("score"))
      val full = run(p, pageSize = 100).rows.map(_.id)
      full shouldBe Seq("p4", "p2", "p3", "p1") // score DESC: 100, 50, 20, 5

      val page1 = run(p, pageSize = 2)
      page1.rows.map(_.id) shouldBe Seq("p4", "p2")
      page1.nextCursor shouldBe defined

      val page2 = run(p, cursor = page1.nextCursor, pageSize = 2)
      page2.rows.map(_.id) shouldBe Seq("p3", "p1")

      (page1.rows.map(_.id) ++ page2.rows.map(_.id)) shouldBe full
    }

    "page by order:duration across NULL and non-NULL keys — no gap (union == full)" in {
      // duration is nullable (only p4 has one; p1/p2/p3 are NULL). Without COALESCE in the ORDER BY,
      // the keyset WHERE, and the cursor key, the NULL-keyed rows drop out on page 2+ (a keyset gap).
      val p = plan(includes = tagSet("1girl"), order = Some("duration"))
      val full = run(p, pageSize = 100).rows.map(_.id).toSet
      full shouldBe Set("p1", "p2", "p3", "p4")

      val page1 = run(p, pageSize = 2)
      val page2 = run(p, cursor = page1.nextCursor, pageSize = 2)
      val pagedIds = page1.rows.map(_.id) ++ page2.rows.map(_.id)
      pagedIds.toSet shouldBe full // no gap
      pagedIds.distinct.size shouldBe 4 // no repeat
    }

    "keep order:random stable across pages — no repeat, no skip" in {
      val p = plan(includes = tagSet("1girl"), order = Some("random"))

      val page1 = run(p, pageSize = 2)
      page1.rows should have size 2
      page1.nextCursor shouldBe defined

      val page2 = run(p, cursor = page1.nextCursor, pageSize = 2)
      page2.rows should have size 2

      val first = page1.rows.map(_.id)
      val second = page2.rows.map(_.id)
      first.toSet intersect second.toSet shouldBe empty // no repeat
      (first ++ second).toSet shouldBe Set("p1", "p2", "p3", "p4") // no skip: all 1girl actives

      // The tail page (past the last full page) has no next cursor.
      val page3 = run(p, cursor = page2.nextCursor, pageSize = 2)
      page3.rows shouldBe empty
      page3.nextCursor shouldBe None
    }

    "surface a compile failure as a Left rather than throwing" in {
      val bad = plan(
        includes = tagSet("1girl"),
        predicates = Seq(MetaTerm(Polarity.Include, "filesize", MetaValue.Compare(Cmp.Gt, "1000")))
      )
      executor.search(bad, None, 100).futureValue match
        case Left(err) => err shouldBe CompileError.UnsupportedMetatag("filesize")
        case Right(_) => fail("expected a Left(UnsupportedMetatag)")
    }
  }
