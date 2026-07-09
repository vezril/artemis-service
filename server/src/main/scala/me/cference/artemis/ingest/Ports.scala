package me.cference.artemis.ingest

import apollostorage.grpc.{PutHeader, PutObjectResponse}
import codex.messages.v1.ProcessMediaJob
import me.cference.artemis.grpc.ApolloObjectClient
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.Future

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
