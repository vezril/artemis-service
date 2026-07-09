package me.cference.artemis.messages

import codex.messages.v1.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The HermesMQ wire format for the media pipeline is protobuf's canonical JSON (design-lexicon).
 * These prove Artemis's thin [[MediaMessages]] codec round-trips the shared `codex.messages.v1`
 * types through the readable camelCase form and tolerates unknown fields for forward compatibility.
 */
final class MediaMessagesSpec extends AnyWordSpec with Matchers:

  "MediaMessages" should {

    "round-trip a ProcessMediaJob through canonical JSON" in {
      val job = ProcessMediaJob(
        jobId = "j-1",
        postId = "p-1",
        source = Some(ObjectRef("media", "p-1/source.png")),
        mediaType = "image",
        contentType = "image/png",
        want = Seq("thumb", "sample")
      )
      val json = MediaMessages.toJson(job)
      json should include("\"jobId\"") // canonical JSON = camelCase
      json should include("\"postId\"")
      MediaMessages.fromJson[ProcessMediaJob](json) shouldBe job
    }

    "round-trip a MediaProcessed with metadata, phash and derivatives" in {
      val processed = MediaProcessed(
        jobId = "j-2",
        postId = "p-2",
        status = "ok",
        metadata = Some(
          MediaMetadata(
            width = 1920,
            height = 1080,
            durationSeconds = Some(12.5),
            md5 = "abc",
            filetype = "mp4",
            hasAudio = Some(true)
          )
        ),
        phash = "f00d",
        derivatives = Seq(
          Derivative(
            kind = "thumb",
            ref = Some(ObjectRef("media", "p-2/thumb.webp")),
            width = 320,
            height = 180,
            variant = Some("webp")
          )
        ),
        specVersion = 3
      )
      val json = MediaMessages.toJson(processed)
      json should include("\"jobId\"")
      json should include("\"postId\"")
      MediaMessages.fromJson[MediaProcessed](json) shouldBe processed
    }

    "round-trip a MediaFailed" in {
      val failed = MediaFailed(
        jobId = "j-3",
        postId = "p-3",
        error = Some(JobError(code = "DECODE", message = "unsupported codec")),
        retriable = false
      )
      val json = MediaMessages.toJson(failed)
      json should include("\"jobId\"")
      json should include("\"postId\"")
      MediaMessages.fromJson[MediaFailed](json) shouldBe failed
    }

    "tolerantly decode a MediaProcessed carrying an unknown field" in {
      val json =
        """{"jobId":"j-4","postId":"p-4","status":"ok","phash":"beef","somethingNew":1}"""
      val parsed = MediaMessages.fromJson[MediaProcessed](json)
      parsed.jobId shouldBe "j-4"
      parsed.postId shouldBe "p-4"
      parsed.status shouldBe "ok"
      parsed.phash shouldBe "beef"
    }

    "decode good JSON to a Right via fromJsonEither" in {
      val json = MediaMessages.toJson(MediaFailed(jobId = "j-5", postId = "p-5", retriable = true))
      MediaMessages.fromJsonEither[MediaFailed](json) match
        case Right(m) => m.jobId shouldBe "j-5"
        case Left(e) => fail(s"expected Right, got Left($e)")
    }

    "return a Left for malformed JSON at the wire boundary (poison message)" in {
      // A truncated/corrupt payload must not throw out of the consumer stream — it's a Left the
      // Hermes consumer can route to a dead-letter path.
      MediaMessages.fromJsonEither[MediaProcessed]("{ not valid json").isLeft shouldBe true
    }
  }
