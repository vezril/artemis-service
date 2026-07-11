package me.cference.artemis.domain

/**
 * The pure alias-merge pipeline (auto-tagging spec): fold Argus's raw tag suggestions — emitted
 * from two vocabularies (Danbooru-style + a model's own labels) — into Artemis's canonical
 * suggestion set. The clever bit is that no new mechanism is needed: the SAME alias resolution the
 * write path uses ([[TagCanonicalization.resolveAlias]]) is exactly what unifies the vocabularies.
 *
 * Steps, in order:
 *   1. normalize each raw surface form through [[Tag.from]] (Argus already lower-cases/underscores,
 *      but a malformed label is dropped rather than rejecting the whole batch); 2. alias-resolve
 *      each tag to its terminal canonical name (you grow the alias map as you review — the first
 *      time you see `outdoor`, you alias it to `outdoors` and it merges forever); 3. dedup by
 *      canonical tag, keeping the MAX confidence where models/aliases collapse to one tag
 *      (cross-model agreement naturally surfaces high-confidence tags), keeping that winner's
 *      `source`.
 *
 * The result is ordered by descending confidence so the review queue shows the most-agreed tags
 * first. Only aliases are applied here (not implication expansion): implied parents carry no model
 * confidence, so they are added — if at all — when a human accepts and the write path
 * canonicalizes.
 */
object SuggestionMerge:

  /** One raw suggestion as it arrives from Argus, before canonicalization. */
  final case class RawSuggestion(tag: String, confidence: Double, source: String)

  def merge(raw: Seq[RawSuggestion], graph: TagGraph): Vector[SuggestedTag] =
    raw
      // normalize + drop malformed surface forms; clamp confidence into [0,1] and drop non-finite
      // scores (a stray NaN/Infinity from a model would otherwise poison the max/sort here and the
      // JSON encoding in the projection).
      .flatMap(r =>
        Tag.from(r.tag).toOption.filter(_ => r.confidence.isFinite).map { t =>
          (t, math.max(0.0, math.min(1.0, r.confidence)), r.source)
        }
      )
      // alias-resolve to canonical
      .map((t, confidence, source) =>
        (TagCanonicalization.resolveAlias(t, graph.aliases), confidence, source)
      )
      // dedup by canonical tag, keeping the max-confidence contributor (and its source)
      .groupBy((tag, _, _) => tag)
      .view
      .mapValues(_.maxBy((_, confidence, _) => confidence))
      .values
      .map((tag, confidence, source) => SuggestedTag(tag, confidence, source))
      .toVector
      // most-agreed first; tie-break by tag name for a deterministic order
      .sortBy(s => (-s.confidence, s.tag.value))
