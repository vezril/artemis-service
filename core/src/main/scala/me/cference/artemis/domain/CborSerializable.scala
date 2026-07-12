package me.cference.artemis.domain

/**
 * Marker for types persisted via Pekko's Jackson CBOR serializer (mirrors Apollo's design D4).
 * Declared in `core` as an empty trait (no Pekko dependency) so domain events can extend it; the
 * server binds it to the `jackson-cbor` serializer in config and teaches the ObjectMapper how to
 * handle the value types via a Jackson module — keeping the pure domain free of serialization
 * annotations.
 */
trait CborSerializable
