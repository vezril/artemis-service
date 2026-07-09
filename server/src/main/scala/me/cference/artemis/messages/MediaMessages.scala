package me.cference.artemis.messages

import scalapb.json4s.{JsonFormat, Parser}
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

/**
 * Thin codec for the media pipeline's HermesMQ wire format: protobuf canonical JSON
 * (design-lexicon) over the shared `codex.messages.v1` types. Encoding yields the readable
 * camelCase form; decoding is forward-compatible (unknown fields added in a newer minor version are
 * ignored rather than rejected), matching the tolerant-consumer guidance in the Lexicon README.
 *
 * The generic signature works because every ScalaPB-generated message's companion is available as
 * an implicit `GeneratedMessageCompanion[A]`, so callers write `fromJson[MediaProcessed](json)`
 * with no explicit companion.
 *
 * Two decode variants: [[fromJsonEither]] is total — it returns a `Left` for ill-formed JSON, which
 * is the right shape at the HermesMQ wire boundary (a poison/corrupt message should be routed to a
 * dead-letter path, not throw out of the consumer stream). [[fromJson]] throws on malformed input
 * and is for trusted, known-good JSON (tests, internal round-trips). Both ignore unknown fields.
 */
object MediaMessages:

  // Tolerant, reused parser: absent optionals stay absent, unknown fields are dropped.
  private val parser = new Parser().ignoringUnknownFields

  def toJson[A <: GeneratedMessage](message: A): String =
    JsonFormat.toJsonString(message)

  /** Decode trusted, known-good JSON; throws on malformed input. See [[fromJsonEither]]. */
  def fromJson[A <: GeneratedMessage](json: String)(using
      companion: GeneratedMessageCompanion[A]
  ): A =
    parser.fromJsonString[A](json)

  /**
   * Total decode for the wire boundary: `Left(cause)` on ill-formed JSON (a malformed/poison
   * payload), `Right(message)` otherwise. Unknown fields are still ignored (forward-compatible).
   */
  def fromJsonEither[A <: GeneratedMessage](json: String)(using
      companion: GeneratedMessageCompanion[A]
  ): Either[Throwable, A] =
    try Right(parser.fromJsonString[A](json))
    catch case scala.util.control.NonFatal(cause) => Left(cause)
