package me.cference.artemis.ingest

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pure selection logic behind [[ReadModelNearDuplicates]]: given candidate `(id, phash)` pairs,
 * pick the closest whose Hamming distance is within threshold. No Postgres — the `activePhashes`
 * query itself is covered by the projection IT.
 */
final class NearDuplicatesSpec extends AnyWordSpec with Matchers:

  "ReadModelNearDuplicates.select" should {

    "match a candidate within the Hamming threshold" in {
      // 0x00 vs 0x01 -> distance 1, well within threshold 10.
      ReadModelNearDuplicates.select(Seq("p1" -> "00"), "01", 10) shouldBe Some("p1")
    }

    "not match a candidate beyond the Hamming threshold" in {
      // 0x00 vs 0xff -> distance 8, above threshold 3.
      ReadModelNearDuplicates.select(Seq("p1" -> "00"), "ff", 3) shouldBe None
    }

    "pick the closest candidate when several are within threshold" in {
      // vs "ff": "00" -> 8, "fe" -> 1. Closest is "near".
      ReadModelNearDuplicates.select(Seq("far" -> "00", "near" -> "fe"), "ff", 10) shouldBe
        Some("near")
    }

    "return None when there are no candidates" in {
      ReadModelNearDuplicates.select(Seq.empty, "00", 10) shouldBe None
    }

    "ignore non-comparable candidates (mismatched length counts as no match)" in {
      // "00" vs "0000" is not comparable -> Int.MaxValue, no finite threshold accepts it.
      ReadModelNearDuplicates.select(Seq("p1" -> "0000"), "00", 10) shouldBe None
    }
  }
