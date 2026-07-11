package me.cference.artemis.reprocess

/**
 * What a reprocess re-runs (reprocess-orchestration spec):
 *   - `Derivatives` — re-run Hephaestus to regenerate thumbs/samples/transcodes (a
 *     `ProcessMediaJob` wanting the derivative kinds); stale relative to `derivative_spec_version`.
 *   - `Metadata` — re-run Hephaestus to recompute dimensions/phash only (a `ProcessMediaJob`
 *     wanting no derivatives); shares the derivative-spec version stamp.
 *   - `Tags` — re-run Argus to re-suggest tags (a `TagJob` → the review queue); stale relative to
 *     `tagger_version`.
 *
 * A kind scopes WHAT re-runs, so regenerating thumbnails never touches applied tags and vice versa.
 */
enum ReprocessKind:
  case Derivatives, Metadata, Tags

  /** The read-model version column staleness is measured against for this kind. */
  def versionColumn: String = this match
    case Derivatives | Metadata => "derivative_spec_version"
    case Tags => "tagger_version"

object ReprocessKind:
  def from(raw: String): Option[ReprocessKind] = raw.trim.toLowerCase match
    case "derivatives" => Some(Derivatives)
    case "metadata" => Some(Metadata)
    case "tags" => Some(Tags)
    case _ => None
