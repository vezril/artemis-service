package me.cference.artemis.domain

/**
 * One canonical tag suggested for a post by the auto-tagger (Argus), carried in the post's pending
 * suggestion set (distinct from its applied tags) until a human reviews it. The `tag` is already
 * alias-resolved to Artemis's canonical vocabulary by [[SuggestionMerge]]; `confidence` is the
 * model's score in `[0, 1]` (max across models that agreed on this tag); `source` names the winning
 * model (e.g. `wd-tagger-v3`).
 *
 * A plain case class (public constructor) so Pekko's Jackson mapper (de)serializes it structurally
 * as an element of the `SuggestionsRecorded` event — the `Tag` field rides its own value-type
 * serializer, mirroring how `Derivative` nests inside `MediaProcessed`.
 */
final case class SuggestedTag(tag: Tag, confidence: Double, source: String)
