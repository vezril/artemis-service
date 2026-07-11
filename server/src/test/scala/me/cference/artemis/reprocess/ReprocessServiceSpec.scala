package me.cference.artemis.reprocess

import codex.messages.v1.{ProcessMediaJob, TagJob}
import me.cference.artemis.projection.ReprocessInfo
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * Reprocess orchestration (reprocess-orchestration spec, tasks 2.1/2.2/4.1): selection resolves
 * (DSL / stale / id), a `kind` scopes the job type (Hephaestus `ProcessMediaJob` vs Argus `TagJob`)
 * with no cross-kind effect, and a `stale` re-run is resumable (only still-stale posts enqueue).
 * All primitives are fakes — no DB/Hermes.
 */
final class ReprocessServiceSpec extends AnyWordSpec with Matchers with ScalaFutures:

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  private def info(id: String, noSample: Boolean = false) =
    ReprocessInfo(
      id,
      md5 = s"${id}md5",
      filetype = "image/png",
      sampleRef = if noSample then None else Some(s"media/derivatives/ab/$id/sample.webp")
    )

  /** A service wired with fakes; records published jobs. */
  private class Fixture(
      stale: Seq[String] = Seq.empty,
      search: Map[String, Seq[String]] = Map.empty,
      infos: Map[String, ReprocessInfo] = Map.empty
  ):
    val processJobs = new ConcurrentLinkedQueue[ProcessMediaJob]()
    val tagJobs = new ConcurrentLinkedQueue[TagJob]()
    private var jobSeq = 0
    val service = new ReprocessService(
      stalePostIds = (_, _) => Future.successful(stale),
      searchIds = q => Future.successful(search.getOrElse(q, Seq.empty)),
      infoFor = ids => Future.successful(ids.flatMap(infos.get)),
      publishProcessJob = j =>
        processJobs.add(j)
        Future.unit
      ,
      publishTagJob = j =>
        tagJobs.add(j)
        Future.unit
      ,
      currentDerivativeVersion = 4,
      currentTaggerVersion = 2,
      genJobId = () =>
        jobSeq += 1
        s"job-$jobSeq"
    )

  "ReprocessService — selection" should {

    "resolve a DSL query and enqueue one derivative job per matching post" in {
      val f = new Fixture(
        search = Map("filetype:webm" -> Seq("v1", "v2")),
        infos = Map("v1" -> info("v1"), "v2" -> info("v2"))
      )
      f.service.reprocess("filetype:webm", ReprocessKind.Derivatives).futureValue shouldBe 2
      f.processJobs.asScala.map(_.postId).toSet shouldBe Set("v1", "v2")
      f.processJobs.asScala.foreach(_.want should contain allOf ("thumb", "sample"))
      f.tagJobs shouldBe empty
    }

    "resolve a single id selection" in {
      val f = new Fixture(infos = Map("1284" -> info("1284")))
      f.service.reprocess("id:1284", ReprocessKind.Derivatives).futureValue shouldBe 1
      f.processJobs.asScala.map(_.postId).toList shouldBe List("1284")
    }

    "resolve `stale` from the version query" in {
      val f = new Fixture(
        stale = Seq("s1", "s2", "s3"),
        infos = Map("s1" -> info("s1"), "s2" -> info("s2"), "s3" -> info("s3"))
      )
      f.service.reprocess("stale", ReprocessKind.Derivatives).futureValue shouldBe 3
    }
  }

  "ReprocessService — kind scoping" should {

    "enqueue a metadata job with no wanted derivatives" in {
      val f = new Fixture(infos = Map("m1" -> info("m1")))
      f.service.reprocess("id:m1", ReprocessKind.Metadata).futureValue shouldBe 1
      f.processJobs.asScala.head.want shouldBe empty // metadata only
    }

    "enqueue a TagJob (not a ProcessMediaJob) for kind tags, targeting the sample" in {
      val f = new Fixture(infos = Map("t1" -> info("t1")))
      f.service.reprocess("id:t1", ReprocessKind.Tags).futureValue shouldBe 1
      f.processJobs shouldBe empty // tags never re-run derivatives
      val job = f.tagJobs.asScala.head
      job.postId shouldBe "t1"
      job.sample.map(_.`object`) shouldBe Some("derivatives/ab/t1/sample.webp")
    }

    "skip a tags reprocess for a post with no sample derivative" in {
      val f = new Fixture(infos = Map("t2" -> info("t2", noSample = true)))
      f.service.reprocess("id:t2", ReprocessKind.Tags).futureValue shouldBe 0
      f.tagJobs shouldBe empty
    }
  }

  "ReprocessService — resumability" should {

    "enqueue only still-stale posts on a re-run" in {
      // First run: 3 stale. Simulate a partial backfill by re-running with only 1 still stale.
      val firstRun = new Fixture(
        stale = Seq("a", "b", "c"),
        infos = Map("a" -> info("a"), "b" -> info("b"), "c" -> info("c"))
      )
      firstRun.service.reprocess("stale", ReprocessKind.Derivatives).futureValue shouldBe 3

      val resume = new Fixture(stale = Seq("c"), infos = Map("c" -> info("c")))
      resume.service.reprocess("stale", ReprocessKind.Derivatives).futureValue shouldBe 1
      resume.processJobs.asScala.map(_.postId).toList shouldBe List("c")
    }
  }
