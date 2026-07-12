package me.cference.artemis.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pure tests for Tier-1 near-duplicate ranking (similarity-search spec, task 2.1): within-threshold
 * posts, closest-first, and the empty / non-comparable edges. 64-bit hex phashes constructed with
 * known bit-differences from an all-zero target.
 */
final class SimilaritySearchSpec extends AnyWordSpec with Matchers:

  private val target = "0000000000000000" // 64 bits, all zero
  private val near2 = "0000000000000003" // 0x03 → 2 bits set → distance 2
  private val near6 = "000000000000003f" // 0x3f → 6 bits set → distance 6
  private val far40 = "ffffffffff000000" // 5 × 0xff → 40 bits set → distance 40

  "SimilaritySearch.rank" should {

    "return within-threshold posts, closest first, excluding the far one" in {
      val candidates = Seq("far" -> far40, "b" -> near6, "a" -> near2)
      val result = SimilaritySearch.rank(candidates, target, threshold = 10, limit = 10)
      result shouldBe Seq(SimilarPost("a", 2), SimilarPost("b", 6))
    }

    "honor the limit (still closest-first)" in {
      val candidates = Seq("a" -> near2, "b" -> near6)
      SimilaritySearch.rank(candidates, target, threshold = 10, limit = 1) shouldBe
        Seq(SimilarPost("a", 2))
    }

    "return empty when nothing is within threshold" in {
      SimilaritySearch.rank(Seq("far" -> far40), target, threshold = 10, limit = 10) shouldBe empty
    }

    "never match a non-comparable phash (different length / non-hex)" in {
      val candidates = Seq("odd" -> "abc", "short" -> "0000", "bad" -> "zzzz")
      SimilaritySearch.rank(candidates, target, threshold = 64, limit = 10) shouldBe empty
    }

    "never surface a non-comparable phash even at the maximum threshold" in {
      // The Int.MaxValue "not comparable" sentinel must not slip through when threshold == MaxValue.
      val candidates = Seq("bad" -> "zzzz", "a" -> near2)
      SimilaritySearch.rank(candidates, target, threshold = Int.MaxValue, limit = 10) shouldBe
        Seq(SimilarPost("a", 2))
    }

    "break distance ties deterministically by id" in {
      val candidates = Seq("z" -> near2, "a" -> near2)
      SimilaritySearch.rank(candidates, target, threshold = 10, limit = 10).map(_.id) shouldBe
        Seq("a", "z")
    }
  }
