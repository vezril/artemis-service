package me.cference.artemis.media

import codex.messages.v1.ObjectRef
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import scala.concurrent.Future

/**
 * A media object fetched from storage: its content type, its total size when known, and the body as
 * a lazy byte stream. `size` is `None` only when the store cannot report a length up front; the
 * range path needs a known size, so an unknown-size object is served whole.
 */
final case class MediaObject(
    contentType: String,
    size: Option[Long],
    bytes: Source[ByteString, ?]
)

/**
 * The gateway's storage port. The HTTP media route depends on this seam alone, so it unit-tests
 * against a fake with no real Apollo (and no Docker). `fetch` returns `None` for a missing object
 * so the route can answer `404` without inventing exceptions.
 */
trait MediaSource:
  def fetch(ref: ObjectRef): Future[Option[MediaObject]]

/**
 * Maps a `(md5, variant)` request to the Apollo object it lives under.
 *
 * The constellation stores originals AND derivatives content-addressed under the md5, sharded by
 * the first two md5 characters: `<md5[0:2]>/<md5>/<variant>` in the `media` bucket. The `variant`
 * is the stored filename Hephaestus/Apollo wrote (e.g. `thumb.webp`, `sample.webp`, `720p.mp4`,
 * `original.png`). This convention MUST stay in step with how those services key derivatives; it is
 * deliberately a small, single function so adjusting it is a one-line change.
 */
object MediaResolver:
  val Bucket: String = "media"

  def resolve(md5: String, variant: String): ObjectRef =
    ObjectRef(bucket = Bucket, `object` = s"${md5.take(2)}/$md5/$variant")
