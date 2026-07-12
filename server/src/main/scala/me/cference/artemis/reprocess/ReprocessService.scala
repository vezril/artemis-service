package me.cference.artemis.reprocess

import codex.messages.v1.{ObjectRef, ProcessMediaJob, TagJob}
import me.cference.artemis.projection.ReprocessInfo

import scala.concurrent.{ExecutionContext, Future}

/**
 * The manual reprocess orchestrator (reprocess-orchestration spec): resolve a selection (a search
 * DSL query, `stale`, or `id:<x>`) to active post ids, then enqueue one job per post to the
 * lower-priority backfill lane appropriate to the kind — a Hephaestus `ProcessMediaJob` (to
 * `media.reprocess`) for `Derivatives`/`Metadata`, an Argus `TagJob` (to `media.tag.reprocess`) for
 * `Tags`. Nothing reprocesses automatically; this runs only when the operator triggers it.
 *
 * Resumable + idempotent by construction: `stale` re-resolves against the current version each run
 * (already-current posts drop out), and the jobs are the SAME content-addressed jobs the workers
 * already handle, so a redelivery just overwrites deterministic paths. Returns the number of jobs
 * enqueued.
 *
 * The read/search/publish primitives are injected seams (production: `ReadModelRepository` +
 * `SearchService` + Hermes publishers) so this unit-tests with fakes and no DB/Hermes.
 */
final class ReprocessService(
    stalePostIds: (String, Int) => Future[Seq[String]],
    searchIds: String => Future[Seq[String]],
    infoFor: Seq[String] => Future[Seq[ReprocessInfo]],
    publishProcessJob: ProcessMediaJob => Future[Unit],
    publishTagJob: TagJob => Future[Unit],
    currentDerivativeVersion: Int,
    currentTaggerVersion: Int,
    bucket: String = "media",
    genJobId: () => String = () => java.util.UUID.randomUUID().toString
)(using ec: ExecutionContext):

  /** Resolve the selection + kind and enqueue a reprocess job per matching post. */
  def reprocess(selection: String, kind: ReprocessKind): Future[Int] =
    resolve(selection, kind)
      .flatMap(infoFor)
      .flatMap(infos => foldCount(infos)(info => enqueue(info, kind)))

  private def resolve(selection: String, kind: ReprocessKind): Future[Seq[String]] =
    selection.trim match
      case "stale" => stalePostIds(kind.versionColumn, currentVersionFor(kind))
      case s if s.startsWith("id:") =>
        Future.successful(Option(s.stripPrefix("id:").trim).filter(_.nonEmpty).toSeq)
      case query => searchIds(query)

  private def currentVersionFor(kind: ReprocessKind): Int = kind match
    case ReprocessKind.Derivatives | ReprocessKind.Metadata => currentDerivativeVersion
    case ReprocessKind.Tags => currentTaggerVersion

  /** Build + publish the job for one post; `true` iff a job was enqueued. */
  private def enqueue(info: ReprocessInfo, kind: ReprocessKind): Future[Boolean] =
    kind match
      case ReprocessKind.Derivatives =>
        publishProcessJob(processJob(info, Seq("thumb", "sample"))).map(_ => true)
      case ReprocessKind.Metadata =>
        publishProcessJob(processJob(info, Seq.empty)).map(_ => true)
      case ReprocessKind.Tags =>
        info.sampleRef match
          case Some(ref) => publishTagJob(tagJob(info, ref)).map(_ => true)
          case None => Future.successful(false) // no sample derivative → can't re-tag
    end match

  private def processJob(info: ReprocessInfo, want: Seq[String]): ProcessMediaJob =
    ProcessMediaJob(
      jobId = genJobId(),
      postId = info.id,
      source = Some(ObjectRef(bucket, originalKey(info.md5, info.filetype))),
      mediaType = mediaClassOf(info.filetype),
      contentType = info.filetype,
      want = want
    )

  private def tagJob(info: ReprocessInfo, sampleRef: String): TagJob =
    TagJob(
      postId = info.id,
      sample = Some(objectRefOf(sampleRef)),
      mediaType = mediaClassOf(info.filetype)
    )

  /** Content-addressed original key, mirroring `UploadService.originalKey`. */
  private def originalKey(md5: String, filetype: String): String =
    s"originals/${md5.take(2)}/$md5.${filetype.split('/').lastOption.filter(_.nonEmpty).getOrElse("bin")}"

  /** A stored `bucket/object` ref → an `ObjectRef` (bucket = up to the first `/`). */
  private def objectRefOf(ref: String): ObjectRef =
    ref.split("/", 2) match
      case Array(b, obj) => ObjectRef(b, obj)
      case _ => ObjectRef(bucket, ref)

  private def mediaClassOf(filetype: String): String =
    val top = filetype.takeWhile(_ != '/').trim.toLowerCase
    if top.nonEmpty then top else "application"

  private def foldCount[A](items: Seq[A])(f: A => Future[Boolean]): Future[Int] =
    items.foldLeft(Future.successful(0)) { (acc, item) =>
      acc.flatMap(n => f(item).map(enqueued => if enqueued then n + 1 else n))
    }
