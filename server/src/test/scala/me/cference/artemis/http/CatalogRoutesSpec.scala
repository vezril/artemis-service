package me.cference.artemis.http

import com.typesafe.config.ConfigFactory
import me.cference.artemis.domain.*
import me.cference.artemis.domain.PostCommand.*
import me.cference.artemis.persistence.{PoolEntity, PostEntity}
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.RecipientRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.apache.pekko.util.Timeout
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.duration.DurationInt

/**
 * Route tests for the Catalog write + read-by-id API (OpenSpec task 5.2 and the read-your-writes
 * part of 5.1). Uses the in-memory persistence-testkit journal (no Docker): each entity id is
 * spawned once, so a write and the immediately-following read hit the same live actor and observe
 * read-your-writes with no projection lag.
 */
final class CatalogRoutesSpec
    extends AnyWordSpec
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfterAll:

  // The in-memory testkit journal + snapshot store — no Postgres, no serialization round-trip.
  // The entities declare both a journal and a snapshot store (r2dbc in the shared config), so both
  // plugins are overridden to the in-memory testkit here.
  override def testConfig =
    ConfigFactory
      .parseString("""
        # Don't let the shared runtime config's coordinated-shutdown kill the forked test JVM when
        # the actor system terminates — it races the sbt fork harness (spurious EOFException).
        pekko.coordinated-shutdown.exit-jvm = off
        pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off
      """)
      .withFallback(PersistenceTestKitPlugin.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
      .withFallback(ConfigFactory.load())

  private val testKit: ActorTestKit = ActorTestKit(system.toTyped)

  private given Timeout = Timeout(3.seconds)
  private given org.apache.pekko.actor.typed.ActorSystem[?] = testKit.system

  // One entity per id (single writer). Memoized so a write and a later read share the same actor.
  private var posts: Map[String, RecipientRef[PostEntity.Command]] = Map.empty
  private var pools: Map[String, RecipientRef[PoolEntity.Command]] = Map.empty

  private def postFor(id: String): RecipientRef[PostEntity.Command] =
    posts.getOrElse(
      id, {
        val ref = testKit.spawn(PostEntity(PostId.unsafe(id)))
        posts = posts.updated(id, ref)
        ref
      }
    )

  private def poolFor(id: String): RecipientRef[PoolEntity.Command] =
    pools.getOrElse(
      id, {
        val ref = testKit.spawn(PoolEntity(PoolId.unsafe(id)))
        pools = pools.updated(id, ref)
        ref
      }
    )

  private def routes: Route = CatalogRoutes(postFor, poolFor).routes

  /**
   * Drive a raw command straight to the entity (used to promote a Pending post to Active, which the
   * in-scope HTTP surface can't do — RecordProcessed is the ingest milestone M8).
   */
  private def process(id: String): Unit =
    processWith(id, Vector.empty)

  /** Promote a Pending post to Active carrying the given media derivatives. */
  private def processWith(id: String, derivatives: Vector[Derivative]): Unit =
    val probe = testKit.createTestProbe[StatusReply[Done]]()
    postFor(id) ! PostEntity.Execute(
      RecordProcessed(Dimensions.unsafe(100, 200, None), derivatives, Phash("ph"), Instant.now()),
      probe.ref
    )
    val _ = probe.receiveMessage()

  private def json(body: String): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, body)

  override def afterAll(): Unit =
    testKit.shutdownTestKit()
    super.afterAll()

  "POST /posts" should {
    "create a pending post and return 201 with the post id" in {
      Post(
        "/posts",
        json("""{"id":"p1","md5":"abc","filetype":"image/png"}""")
      ) ~> routes ~> check {
        status shouldBe StatusCodes.Created
        val body = responseAs[String]
        body should include("\"postId\":\"p1\"")
        body should include("\"status\":\"pending\"")
      }
    }

    "return 409 when the post already exists" in {
      Post(
        "/posts",
        json("""{"id":"dup","md5":"abc","filetype":"image/png"}""")
      ) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Post(
        "/posts",
        json("""{"id":"dup","md5":"abc","filetype":"image/png"}""")
      ) ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }
  }

  "GET /posts/{id}" should {
    "return 404 for a never-created post" in {
      Get("/posts/ghost") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "return 200 with pending status right after create (read-your-writes)" in {
      Post("/posts", json("""{"id":"r1","md5":"m","filetype":"image/png"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Get("/posts/r1") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"id\":\"r1\"")
        body should include("\"status\":\"pending\"")
      }
    }

    "expose an active post's derivative refs as {kind, variant} filenames" in {
      Post(
        "/posts",
        json("""{"id":"d1","md5":"abc","filetype":"image/png"}""")
      ) ~> routes ~> check(status shouldBe StatusCodes.Created)
      processWith(
        "d1",
        Vector(
          Derivative("thumbnail", "media/ab/abc/thumb.webp"),
          Derivative("sample", "media/ab/abc/sample.webp")
        )
      )
      Get("/posts/d1") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"status\":\"active\"")
        body should include("\"kind\":\"thumbnail\"")
        body should include("\"variant\":\"thumb.webp\"")
        body should include("\"kind\":\"sample\"")
        body should include("\"variant\":\"sample.webp\"")
        // Only the gateway-relative variant is exposed, never the raw bucket/object ref.
        body should not include "media/ab"
      }
    }

    "return an empty derivatives list for a pending (media-less) post" in {
      Post(
        "/posts",
        json("""{"id":"d2","md5":"abc","filetype":"image/png"}""")
      ) ~> routes ~> check(status shouldBe StatusCodes.Created)
      Get("/posts/d2") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"status\":\"pending\"")
        body should include("\"derivatives\":[]")
      }
    }
  }

  /** Create via HTTP then promote to Active (RecordProcessed is not an in-scope HTTP endpoint). */
  private def createAndProcess(id: String, md5: String = "m", ft: String = "image/png"): Unit =
    Post("/posts", json(s"""{"id":"$id","md5":"$md5","filetype":"$ft"}""")) ~> routes ~> check {
      status shouldBe StatusCodes.Created
    }
    process(id)

  "PATCH /posts/{id}/tags" should {
    "apply the tag edit and reflect it on an immediate GET (read-your-writes)" in {
      createAndProcess("t1")
      Patch("/posts/t1/tags", json("""{"tags":["Cat Ears","1girl"]}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Get("/posts/t1") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        // With TagGraph.empty the canonical set is just the normalized requested set.
        body should include("\"1girl\"")
        body should include("\"cat_ears\"")
      }
    }

    "return 404 for a write to a missing post and journal no event" in {
      Patch("/posts/does-not-exist/tags", json("""{"tags":["x"]}""")) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
      // No event journaled: the entity still folds to Empty.
      Get("/posts/does-not-exist") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }

  "POST/DELETE /posts/{id}/favorite" should {
    "favorite the post and reflect favorited:true on GET" in {
      createAndProcess("f1")
      Post("/posts/f1/favorite") ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Get("/posts/f1") ~> routes ~> check {
        responseAs[String] should include("\"favorited\":true")
      }
    }

    "unfavorite the post and reflect favorited:false on GET" in {
      createAndProcess("f2")
      Post("/posts/f2/favorite") ~> routes ~> check(status shouldBe StatusCodes.OK)
      Delete("/posts/f2/favorite") ~> routes ~> check(status shouldBe StatusCodes.OK)
      Get("/posts/f2") ~> routes ~> check {
        responseAs[String] should include("\"favorited\":false")
      }
    }
  }

  "POST /posts/{id}/score" should {
    "apply the delta and reflect the total on GET" in {
      createAndProcess("s1")
      Post("/posts/s1/score", json("""{"delta":5}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Get("/posts/s1") ~> routes ~> check {
        responseAs[String] should include("\"score\":5")
      }
    }
  }

  "PATCH /posts/{id}/rating" should {
    "set a valid rating and reflect it on GET" in {
      createAndProcess("rt1")
      Patch("/posts/rt1/rating", json("""{"rating":"e"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Get("/posts/rt1") ~> routes ~> check {
        responseAs[String] should include("\"rating\":\"e\"")
      }
    }

    "return 400 for an invalid rating" in {
      createAndProcess("rt2")
      Patch("/posts/rt2/rating", json("""{"rating":"x"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "POST /pools" should {
    "create a pool and return 201" in {
      Post("/pools", json("""{"id":"pool1","name":"My Pool"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
        responseAs[String] should include("\"poolId\":\"pool1\"")
      }
    }

    "return 409 for a duplicate pool" in {
      Post("/pools", json("""{"id":"dp","name":"A"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Post("/pools", json("""{"id":"dp","name":"A"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }
  }

  "GET /pools/{id}" should {
    "return 404 for a never-created pool" in {
      Get("/pools/nope") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }

  "Pool mutations" should {
    "add posts, reorder, and reflect the ordered members on GET" in {
      Post("/pools", json("""{"id":"op","name":"Ordered"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Post("/pools/op/posts", json("""{"postId":"a"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Post("/pools/op/posts", json("""{"postId":"b"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Post("/pools/op/posts", json("""{"postId":"c"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Put("/pools/op/order", json("""{"order":["c","a","b"]}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Get("/pools/op") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val body = responseAs[String]
        body should include("\"id\":\"op\"")
        body should include("\"posts\":[\"c\",\"a\",\"b\"]")
      }
    }

    "remove a post and reflect the shrunk membership on GET" in {
      Post("/pools", json("""{"id":"rp","name":"R"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Post("/pools/rp/posts", json("""{"postId":"x"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Delete("/pools/rp/posts/x") ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Get("/pools/rp") ~> routes ~> check {
        responseAs[String] should include("\"posts\":[]")
      }
    }

    "rename a pool and reflect the new name on GET" in {
      Post("/pools", json("""{"id":"rn","name":"Old"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Patch("/pools/rn", json("""{"name":"New"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Get("/pools/rn") ~> routes ~> check {
        responseAs[String] should include("\"name\":\"New\"")
      }
    }

    "delete a pool so GET returns 404" in {
      Post("/pools", json("""{"id":"del","name":"Gone"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Delete("/pools/del") ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
      Get("/pools/del") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "return 404 when adding a post to a missing pool" in {
      Post("/pools/missing/posts", json("""{"postId":"a"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "return 404 when removing a post that is not a member" in {
      Post("/pools", json("""{"id":"nm","name":"N"}""")) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Delete("/pools/nm/posts/absent") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }
