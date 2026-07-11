package me.cference.artemis.gc

import me.cference.artemis.grpc.ApolloObjectClient

import scala.concurrent.{ExecutionContext, Future}

/** One stored blob: its Apollo key and the content md5 Apollo reports for it. */
final case class BlobRef(key: String, md5: String)

/**
 * Narrow blob-store seam for the deletion lifecycle (purge + orphan sweep): list a prefix and
 * delete a key. The purge/sweep logic is built against this so it unit-tests with a fake, no real
 * Apollo — mirroring the ingest ports.
 */
trait BlobStore:
  /** Every blob under `prefix` (all pages flattened). */
  def list(prefix: String): Future[Seq[BlobRef]]

  /** Permanently delete one blob by key. */
  def delete(key: String): Future[Unit]

/**
 * Apollo-backed [[BlobStore]]: paginates `ListObjects` under a prefix and deletes by key, in the
 * media bucket. Content-addressed keys mean deletes are exact and idempotent.
 */
final class ApolloBlobStore(apollo: ApolloObjectClient, bucket: String, pageSize: Int = 1000)(using
    ec: ExecutionContext
) extends BlobStore:

  def list(prefix: String): Future[Seq[BlobRef]] =
    def page(token: String, acc: Vector[BlobRef]): Future[Seq[BlobRef]] =
      apollo.listObjects(bucket, prefix, pageSize, token).flatMap { resp =>
        val next = acc ++ resp.objects.map(o => BlobRef(o.`object`, o.md5))
        if resp.nextPageToken.isEmpty then Future.successful(next)
        else page(resp.nextPageToken, next)
      }
    page("", Vector.empty)

  def delete(key: String): Future[Unit] =
    apollo.deleteObject(bucket, key).map(_ => ())
