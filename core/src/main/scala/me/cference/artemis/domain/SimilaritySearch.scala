package me.cference.artemis.domain

/** One near-duplicate match: a post id and its perceptual-hash Hamming distance from the target. */
final case class SimilarPost(id: String, distance: Int)

/**
 * Tier-1 "find similar" ranking: near-duplicate search by perceptual-hash Hamming distance
 * (similarity-search spec). Pure and total — reuses [[PerceptualHash.hamming]] (variable-length,
 * non-comparable-safe), so it works over the hex phash already stored per post without assuming a
 * fixed 64-bit width. The candidate list is `(id, phash)` of the posts to compare against (already
 * excluding the source post); non-comparable phashes score `Int.MaxValue` and so never match.
 */
object SimilaritySearch:

  /**
   * Rank `candidates` by Hamming distance to `target`, keep those within `threshold`, order
   * closest-first (ties by id for determinism), and take at most `limit`.
   */
  def rank(
      candidates: Seq[(String, String)],
      target: String,
      threshold: Int,
      limit: Int
  ): Seq[SimilarPost] =
    candidates
      .map((id, phash) => SimilarPost(id, PerceptualHash.hamming(Phash(target), Phash(phash))))
      // Drop the `Int.MaxValue` "not comparable" sentinel explicitly, so it can never match even if
      // `threshold` is itself `Int.MaxValue` (an uncapped high threshold param).
      .filter(sp => sp.distance != Int.MaxValue && sp.distance <= threshold)
      .sortBy(sp => (sp.distance, sp.id))
      .take(limit)
