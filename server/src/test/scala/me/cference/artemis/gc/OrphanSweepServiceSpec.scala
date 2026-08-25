package me.cference.artemis.gc

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * Unit tests for the on-demand orphan sweep (admin-gc spec): a dry-run reports the plan with
 * `deleted = 0` and deletes nothing; a real run deletes only the unreferenced originals and reports
 * the counts; a referenced md5 is never swept; and a failing delete is best-effort — it is left for
 * a future pass and NOT counted, rather than failing the whole sweep. The `referencedMd5s` read and
 * the blob store are plain function/fake seams — no Docker/Apollo/Postgres.
 */
final class OrphanSweepServiceSpec extends AnyWordSpec with Matchers with ScalaFutures:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  private def referenced(md5s: String*): () => Future[Set[String]] =
    () => Future.successful(md5s.toSet)

  /**
   * A blob store serving a fixed listing and recording deletions; can be told to fail some keys.
   */
  private class FakeBlobStore(listed: Seq[BlobRef], failKeys: Set[String] = Set.empty)
      extends BlobStore:
    val deleted = new ConcurrentLinkedQueue[String]()
    def list(prefix: String): Future[Seq[BlobRef]] = Future.successful(listed)
    def delete(key: String): Future[Unit] =
      if failKeys.contains(key) then Future.failed(new RuntimeException(s"boom: $key"))
      else
        deleted.add(key)
        Future.unit

  private val originals = Seq(
    BlobRef("originals/aa/aaa.png", "aaa"),
    BlobRef("originals/bb/bbb.png", "bbb"),
    BlobRef("originals/cc/ccc.png", "ccc")
  )

  "OrphanSweepService.sweep" should {

    "report the plan on a dry-run without deleting anything (deleted = 0)" in {
      val blobs = new FakeBlobStore(originals)
      // aaa + ccc referenced; bbb is debris.
      val service = new OrphanSweepService(referenced("aaa", "ccc"), blobs)

      val result = service.sweep(dryRun = true).futureValue
      result shouldBe OrphanSweepService.SweepResult(scanned = 3, orphans = 1, deleted = 0)
      blobs.deleted shouldBe empty
    }

    "delete only the unreferenced originals on a real run and report the counts" in {
      val blobs = new FakeBlobStore(originals)
      val service = new OrphanSweepService(referenced("aaa", "ccc"), blobs)

      val result = service.sweep(dryRun = false).futureValue
      result shouldBe OrphanSweepService.SweepResult(scanned = 3, orphans = 1, deleted = 1)
      blobs.deleted.asScala.toList shouldBe List("originals/bb/bbb.png")
    }

    "protect a referenced md5 — nothing is swept when every blob is referenced" in {
      val blobs = new FakeBlobStore(originals)
      val service = new OrphanSweepService(referenced("aaa", "bbb", "ccc"), blobs)

      val result = service.sweep(dryRun = false).futureValue
      result shouldBe OrphanSweepService.SweepResult(scanned = 3, orphans = 0, deleted = 0)
      blobs.deleted shouldBe empty
    }

    "be best-effort: a failing delete is left for a future pass and not counted, not fatal" in {
      // Nothing referenced → all three are orphans; the middle delete fails.
      val blobs = new FakeBlobStore(originals, failKeys = Set("originals/bb/bbb.png"))
      val service = new OrphanSweepService(referenced(), blobs)

      val result = service.sweep(dryRun = false).futureValue
      // The sweep completes (not a failed Future) and reports only the two successes.
      result shouldBe OrphanSweepService.SweepResult(scanned = 3, orphans = 3, deleted = 2)
      blobs.deleted.asScala.toList should contain theSameElementsAs List(
        "originals/aa/aaa.png",
        "originals/cc/ccc.png"
      )
    }
  }
