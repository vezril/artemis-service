package me.cference.artemis.ingest

import apollostorage.grpc.{PutHeader, PutObjectResponse}
import codex.messages.v1.ProcessMediaJob
import me.cference.artemis.domain.{PerceptualHash, Phash}
import me.cference.artemis.grpc.ApolloObjectClient
import me.cference.artemis.projection.ReadModelRepository
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}

/**
 * Narrow, Artemis-owned transport & store ports for the async media spine. The concrete HermesMQ
 * client (`lexicon-hermes-grpc`) and the durable dedup store are later milestones; the ingest LOGIC
 * ([[UploadService]], [[MediaResultHandler]]) is built against these seams and exercised with
 * fakes, so it needs no real Hermes/Apollo to unit-test.
 */

/** Publishes a `ProcessMediaJob` (later: to Hermes `media.process` as canonical JSON). */
trait MediaJobPublisher:
  def publish(job: ProcessMediaJob): Future[Unit]

/**
 * The Apollo upload seam. Lets [[UploadService]] be unit-tested with a fake that can simulate a
 * checksum-mismatch failure without a real Apollo.
 */
trait ObjectUploader:
  def put(header: PutHeader, chunks: Source[ByteString, ?]): Future[PutObjectResponse]

/** Thin adapter delegating to the real [[ApolloObjectClient]]. */
final class ApolloObjectUploader(client: ApolloObjectClient) extends ObjectUploader:
  def put(header: PutHeader, chunks: Source[ByteString, ?]): Future[PutObjectResponse] =
    client.putObject(header, chunks)

/**
 * Per-`jobId` dedup so at-least-once redelivery of a Hephaestus result does not double-apply. The
 * durable version ships with the Hermes adapter; this in-memory one is for now.
 */
trait ProcessedJobs:
  def isApplied(jobId: String): Future[Boolean]
  def markApplied(jobId: String): Future[Unit]

/** In-memory [[ProcessedJobs]] backed by a concurrent set — process-local, non-durable. */
final class InMemoryProcessedJobs extends ProcessedJobs:
  private val applied: java.util.Set[String] = ConcurrentHashMap.newKeySet[String]()

  def isApplied(jobId: String): Future[Boolean] =
    Future.successful(applied.contains(jobId))

  def markApplied(jobId: String): Future[Unit] =
    val _ = applied.add(jobId)
    Future.successful(())

/**
 * Near-duplicate lookup port: given a post's phash, find an existing post within the perceptual
 * threshold (if any). The post-processing dup-flag ([[MediaResultHandler]]) is built against this
 * seam so it can be unit-tested with a fake, no Postgres needed.
 */
trait NearDuplicates:
  /** The id of an existing post whose phash is a near-match, or `None` if the post looks unique. */
  def findNear(phash: String, excludeId: String): Future[Option[String]]

object NearDuplicates:
  /** No-op default so existing construction sites need no near-dup dependency. */
  val none: NearDuplicates = new NearDuplicates:
    def findNear(phash: String, excludeId: String): Future[Option[String]] =
      Future.successful(None)

/**
 * Read-model-backed [[NearDuplicates]]: scans the active posts' phashes (excluding the post itself)
 * and returns the closest whose Hamming distance is within `threshold`. Ties and non-comparable
 * phashes fall out naturally — [[PerceptualHash.hamming]] returns `Int.MaxValue` for those, which
 * no finite threshold accepts.
 */
final class ReadModelNearDuplicates(repo: ReadModelRepository, threshold: Int)(using
    ec: ExecutionContext
) extends NearDuplicates:
  def findNear(phash: String, excludeId: String): Future[Option[String]] =
    repo.activePhashes(excludeId).map(ReadModelNearDuplicates.select(_, phash, threshold))

object ReadModelNearDuplicates:
  /**
   * Pure selection: of the `(id, phash)` candidates, the id of the one closest to `phash` within
   * `threshold` (or `None`). Non-comparable phashes score `Int.MaxValue` and so never win.
   */
  def select(candidates: Seq[(String, String)], phash: String, threshold: Int): Option[String] =
    candidates
      .map((id, candidatePhash) =>
        id -> PerceptualHash.hamming(Phash(phash), Phash(candidatePhash))
      )
      .filter((_, distance) => distance <= threshold)
      .minByOption((_, distance) => distance)
      .map((id, _) => id)
