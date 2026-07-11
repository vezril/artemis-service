package me.cference.artemis.projection

import me.cference.artemis.domain.SuggestedTag
import spray.json.*

/**
 * The read-model JSON codec for a post's pending auto-tag suggestions: encode the canonical
 * `Vector[SuggestedTag]` into the `posts.suggestions` JSONB (written by the projection on
 * `SuggestionsRecorded`) and parse it back into [[SuggestionView]]s for the review queue. The JSON
 * is the projection's own controlled output, so parsing is total — a malformed/legacy element
 * degrades to defaults rather than throwing inside the query.
 */
object SuggestionJson:

  def encode(suggestions: Vector[SuggestedTag]): String =
    JsArray(
      suggestions.map(s =>
        JsObject(
          "tag" -> JsString(s.tag.value),
          "confidence" -> JsNumber(s.confidence),
          "source" -> JsString(s.source)
        )
      )
    ).compactPrint

  def parse(json: String): Vector[SuggestionView] =
    json.parseJson match
      case JsArray(elems) =>
        elems.collect { case JsObject(fields) =>
          SuggestionView(
            fields.get("tag").collect { case JsString(v) => v }.getOrElse(""),
            fields.get("confidence").collect { case JsNumber(v) => v.toDouble }.getOrElse(0.0),
            fields.get("source").collect { case JsString(v) => v }.getOrElse("")
          )
        }
      case _ => Vector.empty
