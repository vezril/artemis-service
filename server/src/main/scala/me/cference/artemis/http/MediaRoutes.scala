package me.cference.artemis.http

import me.cference.artemis.media.{MediaObject, MediaSource}
import org.apache.pekko.http.scaladsl.model.headers.{
  ByteRange,
  Range,
  RangeUnits,
  `Accept-Ranges`,
  `Content-Range`
}
import org.apache.pekko.http.scaladsl.model.{
  ContentRange,
  ContentType,
  ContentTypes,
  HttpEntity,
  HttpResponse,
  ResponseEntity,
  StatusCodes
}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext

/**
 * The HTTP media gateway (OpenSpec catalog-api "HTTP media gateway over Apollo"). Streams an Apollo
 * derivative — thumb/sample/original/transcode — to the browser so browsers never speak gRPC, with
 * HTTP range support (`206`) for video seeking.
 *
 * The `(md5, variant)` → object mapping is INJECTED (`resolveRef`): production resolves from the
 * read model's STORED derivative refs first (the exact keys Hephaestus reported) with the
 * [[me.cference.artemis.media.MediaResolver]] convention as a fallback — the convention alone once
 * drifted from Hephaestus's key layout and every image 404'd. Depends only on the [[MediaSource]]
 * port + the resolver fn, so it unit-tests against fakes with no real Apollo or DB.
 */
final class MediaRoutes(
    source: MediaSource,
    resolveRef: (String, String) => scala.concurrent.Future[codex.messages.v1.ObjectRef]
):

  def routes: Route =
    path("media" / Segment / Segment) { (md5, variant) =>
      get {
        onSuccess(resolveRef(md5, variant).flatMap(source.fetch)(ExecutionContext.parasitic)) {
          case None => complete(StatusCodes.NotFound)
          case Some(media) => serve(media)
        }
      }
    }

  /**
   * Serve `media`, honoring a single `Range` header when both a range and a known size are present;
   * otherwise stream the whole object.
   */
  private def serve(media: MediaObject): Route =
    optionalHeaderValueByType(Range) {
      case Some(range) => serveRange(media, range)
      case None => serveFull(media)
    }

  /** Serve the whole object as `200`, advertising range support via `Accept-Ranges: bytes`. */
  private def serveFull(media: MediaObject): Route =
    complete(
      HttpResponse(
        status = StatusCodes.OK,
        headers = List(`Accept-Ranges`(RangeUnits.Bytes)),
        entity = entityOf(media, media.bytes, media.size)
      )
    )

  /**
   * Honor a byte range. Ranges need a known length, so an unknown-size object falls back to the
   * full body. Only the first range of a (possibly multi-range) request is served — sufficient for
   * the video-seek use case. A satisfiable range yields `206` with a sliced body; a range starting
   * at or past the end yields `416` with `Content-Range: bytes * /size`.
   */
  private def serveRange(media: MediaObject, range: Range): Route =
    media.size match
      case None => serveFull(media)
      case Some(size) =>
        range.ranges.headOption.flatMap(resolveRange(_, size)) match
          case Some((start, end)) => servePartial(media, start, end, size)
          case None => unsatisfiable(media, size)

  /**
   * Resolve one requested byte range against a known `size` to an inclusive `(start, end)`, or
   * `None` when it cannot be satisfied (start at/past the end, or an empty suffix). Total over the
   * three `ByteRange` shapes: `start-end`, open-ended `start-`, and suffix `-n`.
   */
  private def resolveRange(range: ByteRange, size: Long): Option[(Long, Long)] =
    val candidate = range match
      // `Slice` guarantees `0 <= first <= last` (its constructor `require`s it), so we only clamp the
      // end to the last byte; `first > end` can't arise here.
      case ByteRange.Slice(first, last) => (first, math.min(last, size - 1))
      case ByteRange.FromOffset(first) => (first, size - 1)
      case ByteRange.Suffix(length) => (math.max(0L, size - length), size - 1)
    // A single validity gate for every shape: satisfiable only within [0, size) with start <= end.
    // This turns a past-the-end offset, an empty/oversized suffix, or a zero-size object into a
    // clean 416 rather than a negative-length entity.
    Option.when(candidate._1 >= 0 && candidate._1 < size && candidate._1 <= candidate._2)(candidate)

  /** Serve `[start, end]` (inclusive) of a known-size object as `206 Partial Content`. */
  private def servePartial(media: MediaObject, start: Long, end: Long, size: Long): Route =
    val length = end - start + 1
    val sliced = MediaRoutes.sliceBytes(media.bytes, start, length)
    complete(
      HttpResponse(
        status = StatusCodes.PartialContent,
        headers = List(
          `Accept-Ranges`(RangeUnits.Bytes),
          `Content-Range`(RangeUnits.Bytes, ContentRange(start, end, size))
        ),
        entity = HttpEntity.Default(contentTypeOf(media.contentType), length, sliced)
      )
    )

  /**
   * A range starting at or past the end is unsatisfiable: `416` with `Content-Range: bytes *
   * /size`. The fetched body substream (the lazy Apollo tail) is cancelled so the underlying gRPC
   * stream is released immediately rather than lingering until the stream subscription times out.
   */
  private def unsatisfiable(media: MediaObject, size: Long): Route =
    extractMaterializer { implicit mat =>
      val _ = media.bytes.runWith(Sink.cancelled)
      complete(
        HttpResponse(
          status = StatusCodes.RangeNotSatisfiable,
          headers = List(`Content-Range`(RangeUnits.Bytes, ContentRange.Unsatisfiable(size)))
        )
      )
    }

  /**
   * Build a streaming entity carrying the object's content type. A known, positive length streams
   * as a `Default` entity (so `Content-Length` is exact); an unknown or zero length falls back to a
   * chunked entity.
   */
  private def entityOf(
      media: MediaObject,
      bytes: Source[ByteString, ?],
      length: Option[Long]
  ): ResponseEntity =
    val ct = contentTypeOf(media.contentType)
    length match
      case Some(n) if n > 0 => HttpEntity.Default(ct, n, bytes)
      case _ => HttpEntity.Chunked.fromData(ct, bytes)

  /** Parse the stored content-type string, defaulting to octet-stream if it is unparseable. */
  private def contentTypeOf(raw: String): ContentType =
    ContentType.parse(raw).getOrElse(ContentTypes.`application/octet-stream`)

object MediaRoutes:
  def apply(
      source: MediaSource,
      resolveRef: (String, String) => scala.concurrent.Future[codex.messages.v1.ObjectRef]
  ): MediaRoutes = new MediaRoutes(source, resolveRef)

  /**
   * A pekko-stream stage that yields the `[start, start + length)` byte window of a chunked stream,
   * slicing across chunk boundaries and emitting nothing outside the window. Tracks only the
   * running absolute position, so it holds no buffer — bytes flow straight through, trimmed at the
   * edges.
   */
  private[http] def sliceBytes(
      bytes: Source[ByteString, ?],
      start: Long,
      length: Long
  ): Source[ByteString, ?] =
    val endExclusive = start + length
    bytes.statefulMapConcat { () =>
      var pos = 0L // absolute offset of the first byte of the incoming chunk
      chunk => {
        val chunkStart = pos
        pos += chunk.length
        val chunkEnd = pos // exclusive
        if chunkEnd <= start || chunkStart >= endExclusive then Nil
        else
          val from = math.max(0L, start - chunkStart).toInt
          val until = math.min(chunk.length.toLong, endExclusive - chunkStart).toInt
          chunk.slice(from, until) :: Nil
      }
    }
