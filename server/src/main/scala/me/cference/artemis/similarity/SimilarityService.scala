package me.cference.artemis.similarity

import me.cference.artemis.domain.{SimilarPost, SimilaritySearch}
import me.cference.artemis.projection.ReadModelRepository

import scala.concurrent.{ExecutionContext, Future}

/**
 * Tier-1 near-duplicate search (similarity-search spec): brute-force perceptual-hash Hamming search
 * over the read model, reusing the pure [[SimilaritySearch.rank]]. Fast enough at personal scale
 * with no index and no separate service. The phash stays the hex string already stored per post
 * (variable-length-safe via `PerceptualHash.hamming`) — no `bit(64)` column needed.
 */
final class SimilarityService(repo: ReadModelRepository)(using ec: ExecutionContext):

  /**
   * Near-duplicates of an existing post: rank all other active posts' phashes against this post's.
   * A post with no phash (pending/failed/absent) has no matches.
   */
  def similarTo(postId: String, threshold: Int, limit: Int): Future[Seq[SimilarPost]] =
    repo.phashOf(postId).flatMap {
      case None => Future.successful(Seq.empty)
      case Some(target) =>
        repo.activePhashes(postId).map(SimilaritySearch.rank(_, target, threshold, limit))
    }

  /**
   * Reverse-image lookup: rank all active posts against a provided phash (excludes nothing). The
   * phash of the dropped image is computed upstream (Hephaestus/Muses) — Artemis has no perceptual
   * hasher — and matched here by the same Hamming search.
   */
  def reverseLookup(phash: String, threshold: Int, limit: Int): Future[Seq[SimilarPost]] =
    repo.activePhashes("").map(SimilaritySearch.rank(_, phash, threshold, limit))
