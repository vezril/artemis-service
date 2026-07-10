package me.cference.artemis.persistence

import com.fasterxml.jackson.core.{JsonGenerator, JsonParser}
import com.fasterxml.jackson.databind.Module.SetupContext
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.databind.{DeserializationContext, SerializerProvider}
import me.cference.artemis.domain.{
  Dimensions,
  PoolEvent,
  PoolId,
  PoolName,
  PostEvent,
  PostId,
  Rating,
  Tag
}

/**
 * Teaches Pekko's Jackson ObjectMapper how to (de)serialize the pure-domain value types carried by
 * the post/pool events and how to handle the polymorphic event ADTs (via mix-ins). Registered
 * through config: `pekko.serialization.jackson.jackson-modules`.
 *
 * Value types have private constructors; deserialization uses the trusted `unsafe` rehydration
 * factories since the values were validated before they were ever persisted. One value
 * (de)serializer covers every position a type appears (scalar field, `Set`/`Vector` element,
 * `Option`, or map key).
 */
final class DomainJacksonModule extends SimpleModule("ArtemisDomainModule"):

  // --- PostId <-> JSON string (scalar, Vector[PostId], Option[PostId]) ---
  addSerializer(
    classOf[PostId],
    new StdSerializer[PostId](classOf[PostId]):
      def serialize(v: PostId, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeString(v.value)
  )
  addDeserializer(
    classOf[PostId],
    new StdDeserializer[PostId](classOf[PostId]):
      def deserialize(p: JsonParser, ctx: DeserializationContext): PostId =
        PostId.unsafe(p.getValueAsString)
  )

  // --- PoolId <-> JSON string ---
  addSerializer(
    classOf[PoolId],
    new StdSerializer[PoolId](classOf[PoolId]):
      def serialize(v: PoolId, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeString(v.value)
  )
  addDeserializer(
    classOf[PoolId],
    new StdDeserializer[PoolId](classOf[PoolId]):
      def deserialize(p: JsonParser, ctx: DeserializationContext): PoolId =
        PoolId.unsafe(p.getValueAsString)
  )

  // --- PoolName <-> JSON string ---
  addSerializer(
    classOf[PoolName],
    new StdSerializer[PoolName](classOf[PoolName]):
      def serialize(v: PoolName, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeString(v.value)
  )
  addDeserializer(
    classOf[PoolName],
    new StdDeserializer[PoolName](classOf[PoolName]):
      def deserialize(p: JsonParser, ctx: DeserializationContext): PoolName =
        PoolName.unsafe(p.getValueAsString)
  )

  // --- Tag <-> JSON string (element of Set[Tag]) ---
  addSerializer(
    classOf[Tag],
    new StdSerializer[Tag](classOf[Tag]):
      def serialize(v: Tag, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeString(v.value)
  )
  addDeserializer(
    classOf[Tag],
    new StdDeserializer[Tag](classOf[Tag]):
      def deserialize(p: JsonParser, ctx: DeserializationContext): Tag =
        Tag.unsafe(p.getValueAsString)
  )

  // --- Rating <-> JSON string (the single canonical letter code) ---
  // jackson-module-scala does not handle Scala 3 enums, so map to/from `code` explicitly. A
  // persisted code is trusted; an impossible bad code throws at this rehydration boundary.
  addSerializer(
    classOf[Rating],
    new StdSerializer[Rating](classOf[Rating]):
      def serialize(v: Rating, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeString(v.code)
  )
  addDeserializer(
    classOf[Rating],
    new StdDeserializer[Rating](classOf[Rating]):
      def deserialize(p: JsonParser, ctx: DeserializationContext): Rating =
        val code = p.getValueAsString
        Rating.from(code) match
          case Right(rating) => rating
          case Left(error) => throw new IllegalArgumentException(error.message)
  )

  // --- Dimensions <-> JSON object {width, height, duration} (private ctor) ---
  addSerializer(
    classOf[Dimensions],
    new StdSerializer[Dimensions](classOf[Dimensions]):
      def serialize(v: Dimensions, gen: JsonGenerator, p: SerializerProvider): Unit =
        gen.writeStartObject()
        gen.writeNumberField("width", v.width)
        gen.writeNumberField("height", v.height)
        v.duration match
          case Some(ms) => gen.writeNumberField("duration", ms)
          case None => gen.writeNullField("duration")
        gen.writeEndObject()
  )
  addDeserializer(
    classOf[Dimensions],
    new StdDeserializer[Dimensions](classOf[Dimensions]):
      def deserialize(p: JsonParser, ctx: DeserializationContext): Dimensions =
        val node = p.getCodec.readTree[com.fasterxml.jackson.databind.JsonNode](p)
        val width = node.get("width").intValue
        val height = node.get("height").intValue
        // `node.get` returns a Java null when the field is absent and a JSON-null node when present
        // but null; `Option` + `filterNot(_.isNull)` collapses both to `None` without a null literal.
        val duration = Option(node.get("duration")).filterNot(_.isNull).map(_.longValue)
        Dimensions.unsafe(width, height, duration)
  )

  override def setupModule(context: SetupContext): Unit =
    super.setupModule(context)
    context.setMixInAnnotations(classOf[PostEvent], classOf[PostEventMixin])
    context.setMixInAnnotations(classOf[PoolEvent], classOf[PoolEventMixin])
