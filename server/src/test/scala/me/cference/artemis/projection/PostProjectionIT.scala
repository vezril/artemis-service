package me.cference.artemis.projection

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import me.cference.artemis.config.PostgresConfig
import me.cference.artemis.domain.*
import me.cference.artemis.domain.PostCommand.*
import me.cference.artemis.persistence.PostEntity
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
 * Integration test (testcontainers): the post projection folds `PostEvent`s from the journal into
 * the `posts`/`tags` read tables (read-model-projections spec 3.1/3.2), the read model rebuilds by
 * replaying from offset zero (3.3), and re-processing already-applied events leaves the
 * upsert-keyed read model unchanged (3.3 edge — idempotent replay).
 */
final class PostProjectionIT
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
  private val md5 = Md5("d41d8cd98f00b204e9800998ecf8427e")
  private val filetype = Filetype("image/png")
  private val phash = Phash("f00dcafe")
  private val dimensions = Dimensions.unsafe(1920, 1080, Some(0L))
  private val derivatives =
    Vector(Derivative("thumbnail", "blob://thumb"), Derivative("sample", "blob://sample"))

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    testKit = ActorTestKit("artemis-post-proj", config())
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

  private def send(id: PostId, cmd: PostCommand): Unit =
    val ref = testKit.spawn(PostEntity(id))
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    ref ! PostEntity.Execute(cmd, probe.ref)
    probe.receiveMessage(10.seconds).isSuccess shouldBe true
    testKit.stop(ref)

  private def runProjection[A](body: => A): A =
    val p = testKit.spawn(ProjectionBehavior(PostProjection(repo)(using testKit.system)))
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

  /** Read a co-occurrence count directly (canonical pair order a < b). */
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

  "The post projection" should {

    "fold a post's lifecycle into the posts read table (3.1)" in {
      val id = PostId.unsafe("p1")
      send(id, CreatePost(id, md5, filetype, now))
      send(id, RecordProcessed(dimensions, derivatives, phash, now))
      send(id, ChangeTags(Set(Tag.unsafe("landscape"), Tag.unsafe("sky")), now))
      send(id, SetRating("e", now))
      send(id, Score(5, now))
      send(id, Favorite(now))

      runProjection {
        eventually {
          val row = repo.getPost("p1").futureValue.getOrElse(fail("post p1 not projected"))
          row.tags should contain theSameElementsAs Seq("landscape", "sky")
          row.status shouldBe "active"
          row.score shouldBe 5
          row.favCount shouldBe 1
          row.rating shouldBe Some("e")
          row.width shouldBe Some(1920)
          row.height shouldBe Some(1080)
          row.parentId shouldBe None
        }
      }
    }

    "maintain tags.post_count across posts gaining and losing a tag (3.2)" in {
      val a = PostId.unsafe("t-a")
      val b = PostId.unsafe("t-b")
      send(a, CreatePost(a, md5, filetype, now))
      send(a, RecordProcessed(dimensions, derivatives, phash, now))
      send(a, ChangeTags(Set(Tag.unsafe("shared"), Tag.unsafe("only_a")), now))
      send(b, CreatePost(b, md5, filetype, now))
      send(b, RecordProcessed(dimensions, derivatives, phash, now))
      send(b, ChangeTags(Set(Tag.unsafe("shared")), now))

      runProjection {
        eventually {
          repo.tagPostCount("shared").futureValue shouldBe 2
          repo.tagPostCount("only_a").futureValue shouldBe 1
        }
      }

      // a loses `shared` → count drops to 1; only_a untouched.
      send(a, ChangeTags(Set(Tag.unsafe("only_a")), now))
      runProjection {
        eventually {
          repo.tagPostCount("shared").futureValue shouldBe 1
          repo.tagPostCount("only_a").futureValue shouldBe 1
        }
      }
    }

    "rebuild the posts read model by replaying from offset zero (3.3)" in {
      val id = PostId.unsafe("rb1")
      send(id, CreatePost(id, md5, filetype, now))
      send(id, RecordProcessed(dimensions, derivatives, phash, now))
      send(id, ChangeTags(Set(Tag.unsafe("rebuild"), Tag.unsafe("gamma")), now))
      send(id, Favorite(now))

      runProjection {
        // Gate on `favCount == 1` — `Favorite` is the LAST event, so once it lands every earlier
        // event (tags, etc.) is applied too. Gating on an earlier event (tags) and then reading a
        // later-event fact (favCount) below would race: the projection applies events in order, so
        // `before` could capture favCount == 0 before `Favorite` is processed.
        eventually {
          val row = repo.getPost("rb1").futureValue.getOrElse(fail("rb1 not projected"))
          row.tags.sorted shouldBe Seq("gamma", "rebuild")
          row.favCount shouldBe 1
        }
      }
      val before = repo.getPost("rb1").futureValue.getOrElse(fail("missing before rebuild"))
      val sharedCountBefore = repo.tagPostCount("rebuild").futureValue

      // Drop the read model AND the projection offsets, then replay from zero.
      truncateReadModel()
      repo.getPost("rb1").futureValue shouldBe None

      runProjection {
        eventually {
          val after = repo.getPost("rb1").futureValue.getOrElse(fail("not rebuilt"))
          after.tags.sorted shouldBe before.tags.sorted
          after.status shouldBe before.status
          after.favCount shouldBe before.favCount
          after.width shouldBe before.width
          repo.tagPostCount("rebuild").futureValue shouldBe sharedCountBefore
          // Co-occurrence must be REBUILT, not re-incremented on top of stale rows: rb1's one pair
          // {gamma, rebuild} recovers to exactly 1 (a truncate that missed tag_cooccurrence would
          // double it to 2).
          coCount("gamma", "rebuild") shouldBe 1
        }
      }
    }

    "leave the read model unchanged when re-run over already-applied events (3.3 edge)" in {
      val id = PostId.unsafe("idem1")
      send(id, CreatePost(id, md5, filetype, now))
      send(id, RecordProcessed(dimensions, derivatives, phash, now))
      send(id, ChangeTags(Set(Tag.unsafe("idem_tag")), now))
      send(id, Favorite(now))
      // Exercise the accumulating score path: two Score commands ⇒ Scored(3) then Scored(5).
      // Because Scored carries the ABSOLUTE total, redelivery must NOT double-count to 10.
      send(id, Score(3, now))
      send(id, Score(2, now))

      runProjection {
        // Gate on `score == 5` — `Scored(5)` is the LAST event, so once it lands `Favorite` (earlier)
        // is applied too. Gating on `favCount` (an earlier event) and then reading `score` (a later
        // event) below would race: the projection applies events in order, so a `favCount == 1` gate
        // can pass while `score` is still 0.
        eventually {
          val row = repo.getPost("idem1").futureValue.getOrElse(fail("idem1 not projected"))
          row.score shouldBe 5
          row.favCount shouldBe 1
        }
      }
      val first = repo.getPost("idem1").futureValue.getOrElse(fail("missing"))
      val tagCountFirst = repo.tagPostCount("idem_tag").futureValue

      // Clear only the offsets (not the read model) and replay: upsert-keyed writes + the
      // read-current-delta post_count + absolute fav_count are all idempotent. Also corrupt
      // idem1's score to a sentinel so the replay-completion gate is MEANINGFUL: only a real
      // re-application of the journal (absolute setScore) restores it, so gating on score == 5
      // genuinely waits for the replay rather than passing vacuously against the (never-cleared)
      // read model.
      Using.resource(
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
      ) { conn =>
        Using.resource(conn.createStatement()) { st =>
          val _ = st.execute(
            "TRUNCATE projection_offset_store, projection_timestamp_offset_store, " +
              "projection_management"
          )
          val _ = st.execute("UPDATE posts SET score = 999 WHERE id = 'idem1'")
        }
      }

      runProjection {
        // All post-replay assertions live inside one `eventually`: the score==5 gate is false
        // until the replay re-touches idem1 (we set it to 999), and every idempotent write must
        // leave the rest unchanged — no drift, no double-count, no transient read.
        eventually {
          val row = repo.getPost("idem1").futureValue.getOrElse(fail("idem1 missing after replay"))
          row.score shouldBe 5 // absolute upsert restores 5 (not doubled to 10), proving re-touch
          row.favCount shouldBe first.favCount
          row.tags shouldBe first.tags
          repo.tagPostCount("idem_tag").futureValue shouldBe tagCountFirst
        }
      }
    }

    "adjust tags.post_count when a post is deleted and restored (S2)" in {
      val shared = PostId.unsafe("del-shared")
      val target = PostId.unsafe("del-target")
      // `del_tag` lives on two posts; `own_tag` only on the target.
      send(shared, CreatePost(shared, md5, filetype, now))
      send(shared, RecordProcessed(dimensions, derivatives, phash, now))
      send(shared, ChangeTags(Set(Tag.unsafe("del_tag")), now))
      send(target, CreatePost(target, md5, filetype, now))
      send(target, RecordProcessed(dimensions, derivatives, phash, now))
      send(target, ChangeTags(Set(Tag.unsafe("del_tag"), Tag.unsafe("own_tag")), now))

      runProjection {
        eventually {
          repo.tagPostCount("del_tag").futureValue shouldBe 2
          repo.tagPostCount("own_tag").futureValue shouldBe 1
        }
      }

      // Delete the target: a deleted post is a tombstone that must not inflate search counts.
      send(target, Delete(now))
      runProjection {
        eventually {
          repo.getPost("del-target").futureValue.map(_.status) shouldBe Some("deleted")
          repo.tagPostCount("del_tag").futureValue shouldBe 1
          repo.tagPostCount("own_tag").futureValue shouldBe 0
        }
      }

      // Restore it: its tags rejoin the membership counts.
      send(target, Restore(now))
      runProjection {
        eventually {
          repo.getPost("del-target").futureValue.map(_.status) shouldBe Some("active")
          repo.tagPostCount("del_tag").futureValue shouldBe 2
          repo.tagPostCount("own_tag").futureValue shouldBe 1
        }
      }
    }

    "project PossibleDuplicateFlagged into the duplicate_of column (4.3)" in {
      val existing = PostId.unsafe("dup-existing")
      val target = PostId.unsafe("dup-target")
      send(existing, CreatePost(existing, md5, filetype, now))
      send(existing, RecordProcessed(dimensions, derivatives, phash, now))
      send(target, CreatePost(target, md5, filetype, now))
      send(target, RecordProcessed(dimensions, derivatives, phash, now))
      send(target, FlagPossibleDuplicate(existing, now))

      runProjection {
        eventually {
          val row =
            repo.getPost("dup-target").futureValue.getOrElse(fail("dup-target not projected"))
          row.duplicateOf shouldBe Some("dup-existing")
        }
      }
    }

    "expose active posts' phashes for detection, excluding self and non-active (4.3)" in {
      val a = PostId.unsafe("aph-a")
      val b = PostId.unsafe("aph-b")
      val stillPending = PostId.unsafe("aph-pending")
      send(a, CreatePost(a, md5, filetype, now))
      send(a, RecordProcessed(dimensions, derivatives, Phash("aaaa"), now))
      send(b, CreatePost(b, md5, filetype, now))
      send(b, RecordProcessed(dimensions, derivatives, Phash("bbbb"), now))
      send(stillPending, CreatePost(stillPending, md5, filetype, now)) // pending: phash NULL

      runProjection {
        eventually {
          repo.getPost("aph-a").futureValue.map(_.status) shouldBe Some("active")
          repo.getPost("aph-b").futureValue.map(_.status) shouldBe Some("active")
        }
      }

      val excludingA = repo.activePhashes("aph-a").futureValue
      excludingA should contain("aph-b" -> "bbbb")
      excludingA.map(_._1) should not contain "aph-a" // self excluded
      excludingA.map(_._1) should not contain "aph-pending" // non-active / no phash excluded
    }
  }
