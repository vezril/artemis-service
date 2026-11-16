package me.cference.artemis.ingest

import apollostorage.grpc.PutHeader
import codex.messages.v1.{ObjectRef, ProcessMediaJob}
import me.cference.artemis.domain.PostCommand.{ChangeTags, CreatePost, Restore, SetSource}
import me.cference.artemis.domain.{Filetype, Md5, PostId, PostState, Tag}
import me.cference.artemis.persistence.PostEntity
import org.apache.pekko.Done
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorSystem, RecipientRef}
import org.apache.pekko.stream.scaladsl.{FileIO, Source}
import org.apache.pekko.util.{ByteString, Timeout}

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.concurrent.Future
import scala.util.control.NonFatal

/** The result of a successful upload: the post's id and its status after the upload. */
final case class UploadResult(postId: PostId, status: String)

/** A body spooled to disk: where it landed, its md5, and how many bytes were written. */
final private case class Spooled(path: Path, md5: String, size: Long)

/**
 * The upload write path (catalog-api HTTP upload + deduplicated-ingest spec). Streams the bytes to
 * Apollo while computing the md5, stores the original content-addressed, then **dedups on md5**:
 *   - a live post already has this md5 → **merge** the new metadata into it (union tags, append
 *     source, keep rating), NO new post — storage stays 1:1;
 *   - a **soft-deleted** post matches → **restore** it (and merge), since re-uploading something
 *     you deleted brings it back;
 *   - otherwise → **create** a fresh pending post and publish a `ProcessMediaJob`.
 * A purged md5 leaves no read-model row, so it correctly reads as absent → a fresh upload.
 *
 * Because storage is content-addressed, re-`put`-ting an existing md5 is harmless (same bytes, same
 * key), so the put runs before the dedup branch unconditionally. `tags`/`source` default empty —
 * the current HTTP endpoint carries no metadata, so a production duplicate upload is a
 * dedup/restore; the metadata-merge activates once the endpoint (or Muses) supplies tags/source. A
 * merge into a still-`pending` duplicate is a plain dedup (content can't be edited until active).
 *
 * **The body is spooled to disk, never held in memory.** Both the content-addressed key and
 * Apollo's `expected_md5` need the hash of the WHOLE body before the put can start, and an HTTP
 * entity is a one-shot stream — so the bytes are written to a temp file while the md5 is computed
 * incrementally, then streamed from that file to Apollo. Memory stays flat at one chunk regardless
 * of object size; the cost is transient disk in `spoolDirectory` (default: the JVM temp dir,
 * configurable via `artemis.ingest.spool-dir` so a container can point it at a sized volume). The
 * temp file is deleted on every path, success or failure.
 *
 * This is what the ingest spec always required — "stream the bytes to Apollo while computing the
 * md5" — and it matters now that producers upload VIDEO: an earlier revision folded the stream into
 * a single `ByteString` first, which is survivable for images and fatal for a multi-gigabyte video
 * (it is JVM heap).
 *
 * The `lookupMd5`/ports are injected seams (production: `ReadModelRepository.findByMd5` etc.) so
 * this unit-tests with fakes and no real Apollo/Hermes/DB. `postFor` mirrors `CatalogRoutes`.
 *
 * Known limitation: the dedup check (`lookupMd5` → create) is not atomic, so two SIMULTANEOUS
 * uploads of the same brand-new md5 can both read "absent" and both create a post — momentarily
 * breaking the 1:1 invariant. At personal scale (effectively one uploader) the window is
 * negligible; a stronger guarantee would need a unique-md5 constraint reconciled with the
 * projection's upsert, deferred until it matters.
 */
final class UploadService(
    uploader: ObjectUploader,
    publisher: MediaJobPublisher,
    postFor: String => RecipientRef[PostEntity.Command],
    lookupMd5: String => Future[Option[(String, String)]] = _ => Future.successful(None),
    genId: () => String = () => UUID.randomUUID().toString,
    genJobId: () => String = () => UUID.randomUUID().toString,
    spoolDirectory: Path = Paths.get(sys.props.getOrElse("java.io.tmpdir", "/tmp"))
)(using system: ActorSystem[?], timeout: Timeout):

  // MDC-propagating so the correlation id the HTTP edge set on the request thread rides this
  // service's async chain onto the published `ProcessMediaJob.correlation_id` and the Apollo call
  // metadata (request-tracing outbound propagation from the ingest path).
  private given scala.concurrent.ExecutionContext =
    me.cference.artemis.tracing.MdcPropagatingExecutionContext(system.executionContext)
  private val Bucket = "media"

  def upload(
      bytes: Source[ByteString, ?],
      contentType: String,
      mediaType: String,
      tags: Set[Tag] = Set.empty,
      source: Option[String] = None
  ): Future[UploadResult] =
    // Spool first: the key and the ingest checksum both need the md5 of the whole body, and the
    // entity can only be read once. The temp file is removed on every exit path below.
    spoolToDisk(bytes).flatMap { spooled =>
      store(spooled, contentType, mediaType, tags, source)
        .andThen { case _ => discard(spooled.path) }
    }

  /** Put the spooled file, then dedup/create. Split out so cleanup wraps the whole tail. */
  private def store(
      spooled: Spooled,
      contentType: String,
      mediaType: String,
      tags: Set[Tag],
      source: Option[String]
  ): Future[UploadResult] =
    val key = originalKey(spooled.md5, contentType)
    val header =
      PutHeader(
        bucket = Bucket,
        `object` = key,
        contentType = contentType,
        expectedMd5 = spooled.md5
      )
    for
      // A checksum mismatch fails this Future → no Post created/merged, no job published.
      _ <- uploader.put(header, FileIO.fromPath(spooled.path))
      result <- dedupeOrCreate(spooled.md5, key, contentType, mediaType, tags, source)
    yield result

  /**
   * Write the body to a temp file, hashing as it goes. `map` (not `wireTap`) does the digesting:
   * `wireTap` is best-effort and may drop elements under backpressure, which would silently produce
   * the WRONG md5 — a wrong content-addressed key and a bogus dedup result.
   */
  private def spoolToDisk(bytes: Source[ByteString, ?]): Future[Spooled] =
    Files.createDirectories(spoolDirectory)
    val path = Files.createTempFile(spoolDirectory, "artemis-upload-", ".part")
    val digest = MessageDigest.getInstance("MD5")

    bytes
      .map { chunk =>
        digest.update(chunk.asByteBuffer)
        chunk
      }
      .runWith(FileIO.toPath(path))
      .map(io => Spooled(path, hex(digest.digest()), io.count))
      .recoverWith { case error =>
        // A failed or abandoned upload must not leave a multi-gigabyte .part behind.
        discard(path)
        Future.failed(error)
      }

  /** Best-effort temp-file removal: a cleanup failure must not mask the upload's own outcome. */
  private def discard(path: Path): Unit =
    try
      val _ = Files.deleteIfExists(path)
    catch case NonFatal(_) => ()

  /** Branch on whether a post already carries this md5 (deduplicated-ingest). */
  private def dedupeOrCreate(
      md5: String,
      key: String,
      contentType: String,
      mediaType: String,
      tags: Set[Tag],
      source: Option[String]
  ): Future[UploadResult] =
    lookupMd5(md5).flatMap {
      case Some((id, "deleted")) =>
        // Re-uploading a soft-deleted image restores it (blobs were retained), then merges.
        execute(id, Restore(now()))
          .flatMap(_ => mergeMetadata(id, tags, source))
          .map(_ => UploadResult(PostId.unsafe(id), "active"))
      case Some((id, "active")) =>
        // Live duplicate: merge metadata into the existing post; no new post, no new job.
        mergeMetadata(id, tags, source).map(_ => UploadResult(PostId.unsafe(id), "active"))
      case Some((id, "pending")) =>
        // Duplicate still processing: dedup only (content can't be edited on a pending post yet).
        Future.successful(UploadResult(PostId.unsafe(id), "pending"))
      case _ =>
        // Absent, or only a dead `failed` post carries the md5 → create a fresh post + job.
        createFresh(md5, key, contentType, mediaType)
    }

  /** Union `tags` into the post's applied set and append `source`; keeps the existing rating. */
  private def mergeMetadata(id: String, tags: Set[Tag], source: Option[String]): Future[Done] =
    val withTags =
      if tags.isEmpty then Future.successful(Done)
      else
        postFor(id)
          .ask[PostState](PostEntity.Get(_))
          .flatMap {
            case PostState.Active(_, _, content) =>
              execute(id, ChangeTags(content.tags ++ tags, now()))
            case _ => Future.successful(Done) // not active (e.g. still pending): skip the merge
          }
    withTags.flatMap { _ =>
      source match
        case Some(s) => execute(id, SetSource(s, now()))
        case None => Future.successful(Done)
    }

  private def createFresh(
      md5: String,
      key: String,
      contentType: String,
      mediaType: String
  ): Future[UploadResult] =
    val id = PostId.unsafe(genId())
    for
      _ <- createPost(id, md5, contentType)
      _ <- publisher.publish(job(id, key, contentType, mediaType))
    yield UploadResult(id, "pending")

  private def execute(id: String, command: me.cference.artemis.domain.PostCommand): Future[Done] =
    postFor(id).askWithStatus(PostEntity.Execute(command, _))

  private def createPost(id: PostId, md5: String, contentType: String): Future[Done] =
    execute(id.value, CreatePost(id, Md5(md5), Filetype(contentType), now()))

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

  private def hex(digest: Array[Byte]): String =
    digest.map(b => f"${b & 0xff}%02x").mkString

  private def now(): Instant = Instant.now()
