package me.cference.artemis.hermes

/**
 * Artemis's identity on the HermesMQ bus, stamped onto every publish (`producer_id`) and pull
 * (`consumer_id`). Observability-only by contract (active-producer/consumer metrics + log MDC on
 * the broker, per-identity rows in the Hermes console) — it never affects whether or how messages
 * are published or leased.
 */
object HermesIdentity:
  val ProducerId: String = "artemis"
  val ConsumerId: String = "artemis"
