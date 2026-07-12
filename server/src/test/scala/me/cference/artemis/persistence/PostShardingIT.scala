package me.cference.artemis.persistence

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import com.typesafe.config.{Config, ConfigFactory}
import me.cference.artemis.domain.*
import me.cference.artemis.domain.PostCommand.*
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.cluster.MemberStatus
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.typed.{Cluster, Join}
import org.apache.pekko.util.Timeout
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
 * Verifies Cluster Sharding for post entities in a single-node cluster (service-runtime spec):
 * commands routed via `PostSharding.entityRef` reach one entity, the persistence id stays
 * `post|<id>` (proving single-writer routing through the real journal), a `Get` reads the folded
 * state back, and the injected tag-graph supplier is consulted when canonicalizing `ChangeTags`.
 */
final class PostShardingIT
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

  private given Timeout = Timeout(10.seconds)
  private var testKit: ActorTestKit = scala.compiletime.uninitialized
  private var sharding: ClusterSharding = scala.compiletime.uninitialized

  private val now = Instant.parse("2026-07-08T00:00:00Z")
  private val md5 = Md5("d41d8cd98f00b204e9800998ecf8427e")
  private val filetype = Filetype("image/png")
  private val dimensions = Dimensions.unsafe(1920, 1080, Some(0L))
  private val derivatives = Vector(Derivative("thumbnail", "blob://thumb"))
  private val phash = Phash("f00dcafe")

  // A graph that aliases `catgirl -> cat_girl`, so canonicalization through the supplier is
  // observable in the folded state (not just a pass-through of the requested names).
  private val graph = TagGraph(Map(Tag.unsafe("catgirl") -> Tag.unsafe("cat_girl")), Map.empty)

  override def afterStart(): Unit =
    val _ = Class.forName("org.postgresql.Driver")
    applySchema()
    testKit = ActorTestKit("artemis-post-sharding", config())
    val cluster = Cluster(testKit.system)
    cluster.manager ! Join(cluster.selfMember.address)
    eventually(cluster.selfMember.status shouldBe MemberStatus.Up)
    sharding = PostSharding.init(testKit.system, () => graph)

  override def beforeStop(): Unit =
    if testKit != null then testKit.shutdownTestKit() // scalafix:ok DisableSyntax.null

  private def config(): Config =
    ConfigFactory
      .parseString(s"""
        pekko.actor.provider = "cluster"
        pekko.remote.artery.canonical.hostname = "127.0.0.1"
        pekko.remote.artery.canonical.port = 0
        pekko.cluster.jmx.multi-mbeans-in-same-jvm = on
        pekko.cluster.downing-provider-class = "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
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

  private def run(id: String, cmd: PostCommand): Done =
    PostSharding
      .entityRef(sharding, id)
      .askWithStatus[Done](replyTo => PostEntity.Execute(cmd, replyTo))
      .futureValue

  private def state(id: String): PostState =
    PostSharding.entityRef(sharding, id).ask[PostState](PostEntity.Get(_)).futureValue

  private def seqNrs(persistenceId: String): Seq[Long] =
    Using.resource(
      DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
    ) { conn =>
      Using.resource(conn.createStatement()) { st =>
        val rs = st.executeQuery(
          s"SELECT seq_nr FROM event_journal WHERE persistence_id = '$persistenceId' ORDER BY seq_nr"
        )
        Iterator.continually(rs).takeWhile(_.next()).map(_.getLong("seq_nr")).toList
      }
    }

  "Cluster-sharded post entities" should {

    "route to one entity with persistence id post|<id> and read the folded state back" in {
      val id = "p1"
      run(id, CreatePost(PostId.unsafe(id), md5, filetype, now)) shouldBe Done
      run(id, RecordProcessed(dimensions, derivatives, phash, now)) shouldBe Done
      run(id, ChangeTags(Set(Tag.unsafe("catgirl")), now)) shouldBe Done

      // All three commands landed on the one sharded entity's journal, in order.
      seqNrs("post|p1") shouldBe Seq(1L, 2L, 3L)

      // Get reads the folded state; the injected graph canonicalized catgirl -> cat_girl.
      state(id) match
        case active: PostState.Active => active.content.tags should contain(Tag.unsafe("cat_girl"))
        case other => fail(s"expected an Active post, got $other")
    }
  }
