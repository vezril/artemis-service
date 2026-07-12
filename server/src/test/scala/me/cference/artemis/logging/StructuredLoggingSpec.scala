package me.cference.artemis.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, LoggerContext}
import ch.qos.logback.core.OutputStreamAppender
import net.logstash.logback.encoder.LogstashEncoder
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory
import spray.json.*

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Pins the JSON-log schema that the container's `json` appender (in `logback.xml`) emits, so it
 * stays constellation-consistent for Loki (`structured-logging` spec). Attaches the same
 * `LogstashEncoder` + `service` custom field the config wires to a capturing appender and logs
 * through the real pipeline (so MDC/throwable handling is exercised as in production).
 */
final class StructuredLoggingSpec extends AnyWordSpec with Matchers:

  /** Log one ERROR (with an exception) through a LogstashEncoder appender and capture the JSON. */
  private def encodeError(): (String, JsObject) =
    val context = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    val out = new ByteArrayOutputStream()
    val encoder = new LogstashEncoder()
    encoder.setContext(context)
    encoder.setCustomFields("""{"service":"artemis"}""")
    val appender = new OutputStreamAppender[ILoggingEvent]()
    appender.setContext(context)
    appender.setEncoder(encoder)
    appender.setOutputStream(out)
    encoder.start()
    appender.start()

    val logger = context.getLogger("me.cference.artemis.Example")
    logger.setAdditive(false) // capture only; don't also hit the console appender
    logger.addAppender(appender)
    logger.setLevel(Level.ERROR)
    try logger.error("processing failed", new RuntimeException("kaboom"))
    finally
      appender.stop()
      logger.detachAppender(appender)
    val raw = out.toString(UTF_8).trim
    (raw, raw.parseJson.asJsObject)

  "The JSON log encoder" should {

    "emit an error as a single-line JSON object with the constellation field schema" in {
      val (raw, json) = encodeError()
      val fields = json.fields
      fields("service") shouldBe JsString("artemis")
      fields("level") shouldBe JsString("ERROR")
      fields("logger_name") shouldBe JsString("me.cference.artemis.Example")
      fields("message") shouldBe JsString("processing failed")
      fields.keySet should contain allOf ("@timestamp", "thread_name")
      // the exception is carried as an extractable field, not multi-line free text
      fields.keySet should contain("stack_trace")
      raw should not include "\n" // single line (stack-trace newlines are escaped within the string)
    }
  }
