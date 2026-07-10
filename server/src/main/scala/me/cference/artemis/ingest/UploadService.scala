package me.cference.artemis.ingest

import apollostorage.grpc.PutHeader
import codex.messages.v1.{ObjectRef, ProcessMediaJob}
import me.cference.artemis.domain.PostCommand.CreatePost
import me.cference.artemis.domain.{Filetype, Md5, PostId}
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.{ByteString, Timeout}

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.concurrent.Future

/** The result of a successful upload: the new post's id and its initial (always pending) status. */
final case class UploadResult(postId: PostId, status: String)

/**
 * The upload write path (OpenSpec task 4.1). Streams the bytes to Apollo while computing the md5,
 * stores the original content-addressed, seeds a `pending` Post, and publishes a `ProcessMediaJob`
 * — all through the Artemis-owned [[ObjectUploader]]/[[MediaJobPublisher]] ports, so it unit-tests
 * with fakes and no real Apollo/Hermes.
 *
 * A checksum mismatch (the `put` Future fails) aborts before any Post is created or job published:
 * the `put` step precedes both, so short-circuiting the for-comprehension leaves no side effect.
 *
 * Known gap (accepted at personal scale): the create→publish steps are not atomic — if `publish`
 * fails after `CreatePost` succeeds, a `pending` Post is left with no `ProcessMediaJob` enqueued.
 * Reconciling/retrying such orphan-pending posts (a durable outbox) ships with the concrete Hermes
 * wiring; here the failed Future surfaces the error to the caller.
 *
 * `postFor` mirrors [[me.cference.artemis.http.CatalogRoutes]] — production supplies
 * `ClusterSharding.entityRefFor`, tests a locally-spawned actor. `genId`/`genJobId` are injected so
 * a test can pin the generated ids; production uses fresh UUIDs. Real sequential ids / md5-dedup
 * are a later concern.
 */
final class UploadService(
    uploader: ObjectUploader,
    publisher: MediaJobPublisher,
    postFor: String => RecipientRef[PostEntity.Command],
    genId: () => String = () => UUID.randomUUID().toString,
    genJobId: () => String = () => UUID.randomUUID().toString
)(using system: ActorSystem[?], timeout: Timeout):

  private val Bucket = "media"

  def upload(
      bytes: Source[ByteString, ?],
      contentType: String,
      mediaType: String
  ): Future[UploadResult] =
    given scala.concurrent.ExecutionContext = system.executionContext
    for
      // Buffer the stream to compute the md5 (fine at personal scale); re-stream the same bytes.
      buffered <- bytes.runFold(ByteString.empty)(_ ++ _)
      md5 = md5Hex(buffered)
      key = originalKey(md5, contentType)
      header = PutHeader(
        bucket = Bucket,
        `object` = key,
        contentType = contentType,
        expectedMd5 = md5
      )
      // A checksum mismatch fails this Future → no Post created, no job published.
      _ <- uploader.put(header, Source.single(buffered))
      id = PostId.unsafe(genId())
      _ <- createPost(id, md5, contentType)
      _ <- publisher.publish(job(id, key, contentType, mediaType))
    yield UploadResult(id, "pending")

  private def createPost(id: PostId, md5: String, contentType: String): Future[Done] =
    postFor(id.value).askWithStatus(
      PostEntity.Execute(CreatePost(id, Md5(md5), Filetype(contentType), Instant.now()), _)
    )

  private def job(
      id: PostId,
      key: String,
      contentType: String,
      mediaType: String
  ): ProcessMediaJob =
    ProcessMediaJob(
      jobId = genJobId(),
      postId = id.value,
      source = Some(ObjectRef(Bucket, key)),
      mediaType = mediaType,
      contentType = contentType,
      want = Seq("thumb", "sample")
    )

  /**
   * Content-addressed key: `originals/<md5[0:2]>/<md5>.<ext>`, ext derived from the content type.
   */
  private def originalKey(md5: String, contentType: String): String =
    s"originals/${md5.take(2)}/$md5.${extensionOf(contentType)}"

  /** Extension = the subtype of the MIME type (`image/png` → `png`); `bin` when absent. */
  private def extensionOf(contentType: String): String =
    contentType.split('/').lastOption.filter(_.nonEmpty).getOrElse("bin")

  private def md5Hex(bytes: ByteString): String =
    MessageDigest
      .getInstance("MD5")
      .digest(bytes.toArray)
      .map(b => f"${b & 0xff}%02x")
      .mkString
