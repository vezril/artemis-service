package me.cference.artemis.tags

import me.cference.artemis.domain.{Tag, TagGraph}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/**
 * Unit test for [[TagGraphCache]] (service-runtime spec — tag-graph cache). Verifies the snapshot
 * starts empty, `refresh` atomically swaps it, and successive refreshes track the loader — all with
 * a fake in-memory loader (no DB).
 */
final class TagGraphCacheSpec extends AnyWordSpec with Matchers with ScalaFutures:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  private val g1 = TagGraph(Map(Tag.unsafe("catgirl") -> Tag.unsafe("cat_girl")), Map.empty)
  private val g2 = TagGraph(
    Map(Tag.unsafe("catgirl") -> Tag.unsafe("cat_girl")),
    Map(Tag.unsafe("cat_girl") -> Set(Tag.unsafe("animal_ears")))
  )

  "TagGraphCache" should {

    "start empty before the first refresh" in {
      val cache = new TagGraphCache(() => Future.successful(g1))
      cache.current shouldBe TagGraph.empty
    }

    "atomically swap the snapshot on refresh" in {
      val cache = new TagGraphCache(() => Future.successful(g1))
      cache.refresh().futureValue shouldBe g1
      cache.current shouldBe g1
      cache.snapshot() shouldBe g1
    }

    "track the loader across successive refreshes" in {
      val calls = new AtomicInteger(0)
      val cache =
        new TagGraphCache(() => Future.successful(if calls.getAndIncrement() == 0 then g1 else g2))
      cache.refresh().futureValue shouldBe g1
      cache.current shouldBe g1
      cache.refresh().futureValue shouldBe g2
      cache.current shouldBe g2
    }
  }
