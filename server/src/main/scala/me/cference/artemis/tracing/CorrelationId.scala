package me.cference.artemis.tracing

import io.codex.lexicon.CorrelationNames
import org.slf4j.MDC

import java.security.SecureRandom

/**
 * The per-unit-of-work correlation id (request-tracing). The names come from the shared Lexicon
 * `CorrelationNames` (one source of truth across the constellation — no per-service
 * re-declaration); this object adds the two behaviours codegen can't: minting a fresh id and
 * reading the current one from the MDC (the source of truth for outbound propagation).
 *
 * Trust posture is per-boundary (see the route wrapper / consumer): the HTTP edge mints and ignores
 * any client value (anti-injection); a Hermes-delivered message adopts its `correlation_id`.
 */
object CorrelationId:

  /** MDC key → promoted to a top-level JSON log field by the Logstash encoder. */
  val MdcKey: String = CorrelationNames.LogField // "correlationId"

  /** HTTP response header echoed to the caller (title-case). */
  val HttpHeader: String = CorrelationNames.HttpHeader // "X-Correlation-Id"

  /** gRPC / HTTP-2 metadata key on outbound Apollo calls (lower-case, required). */
  val MetadataKey: String = CorrelationNames.GrpcMeta // "x-correlation-id"

  // SecureRandom for an unguessable id (the edge mints one per request and must not be predictable
  // for the anti-injection posture to mean anything). Its `nextInt` synchronizes internally, so at
  // high request rates this is a (single) contention point — acceptable at this scale; if it ever
  // bites, seed a `ThreadLocalRandom` from SecureRandom instead of trading away unpredictability.
  private val Rng = SecureRandom()
  private val Alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
  private val Length = 12

  /** A fresh, short, URL-safe token. Uniqueness + log-friendliness are all that matter. */
  def mint(): String =
    LazyList.continually(Alphabet(Rng.nextInt(Alphabet.length))).take(Length).mkString

  /** The id currently in the MDC, if any — the source outbound propagation reads. */
  def current(): Option[String] = Option(MDC.get(MdcKey)).filter(_.nonEmpty)

  /** An adopted id if non-empty, otherwise a freshly minted one (delivery-with-no-id case). */
  def adoptOrMint(delivered: String): String =
    if delivered.nonEmpty then delivered else mint()
