package me.cference.artemis.domain

import me.cference.artemis.domain.SuggestionMerge.RawSuggestion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * The alias-merge pipeline (auto-tagging spec, task 2.1): raw suggestions from two vocabularies
 * canonicalize through the SAME alias resolution the write path uses, and dedup keeps the max
 * confidence where models agree — surfacing agreed tags to the top.
 */
final class SuggestionMergeSpec extends AnyWordSpec with Matchers:

  // outdoor (a model's label) aliases to the canonical outdoors.
  private val graph = TagGraph(
    aliases = Map(Tag.unsafe("outdoor") -> Tag.unsafe("outdoors")),
    implications = Map.empty
  )

  "SuggestionMerge.merge" should {

    "alias-resolve raw tags into the canonical vocabulary" in {
      val merged = SuggestionMerge.merge(Seq(RawSuggestion("outdoor", 0.8, "ram")), graph)
      merged.map(_.tag) shouldBe Vector(Tag.unsafe("outdoors"))
    }

    "dedup tags that collapse to one canonical, keeping the max confidence and its source" in {
      // outdoors(wd,.9) and outdoor(ram,.8) both resolve to outdoors → one suggestion, max .9.
      val merged = SuggestionMerge.merge(
        Seq(RawSuggestion("outdoors", 0.9, "wd"), RawSuggestion("outdoor", 0.8, "ram")),
        graph
      )
      merged.map(_.tag) shouldBe Vector(Tag.unsafe("outdoors"))
      merged.head.confidence shouldBe 0.9
      merged.head.source shouldBe "wd"
    }

    "order by descending confidence so agreed tags surface first" in {
      // tree agreed by both models (max .9) outranks a single-model woman(.6).
      val merged = SuggestionMerge.merge(
        Seq(
          RawSuggestion("woman", 0.6, "ram"),
          RawSuggestion("tree", 0.7, "wd"),
          RawSuggestion("tree", 0.9, "ram")
        ),
        graph
      )
      merged.map(_.tag) shouldBe Vector(Tag.unsafe("tree"), Tag.unsafe("woman"))
      merged.head.confidence shouldBe 0.9
    }

    "drop malformed surface forms rather than failing the batch" in {
      val merged = SuggestionMerge.merge(
        Seq(RawSuggestion("good_tag", 0.5, "wd"), RawSuggestion("  ", 0.9, "wd")),
        graph
      )
      merged.map(_.tag) shouldBe Vector(Tag.unsafe("good_tag"))
    }

    "drop non-finite confidences and clamp out-of-range ones into [0,1]" in {
      val merged = SuggestionMerge.merge(
        Seq(
          RawSuggestion("nan_tag", Double.NaN, "wd"),
          RawSuggestion("inf_tag", Double.PositiveInfinity, "wd"),
          RawSuggestion("hot_tag", 1.5, "wd"), // clamps to 1.0
          RawSuggestion("cold_tag", -0.3, "wd") // clamps to 0.0
        ),
        graph
      )
      merged.map(_.tag) should contain theSameElementsAs Seq(
        Tag.unsafe("hot_tag"),
        Tag.unsafe("cold_tag")
      )
      merged.map(_.confidence).foreach(c => c should (be >= 0.0 and be <= 1.0))
    }

    "return empty for no raw suggestions" in {
      SuggestionMerge.merge(Seq.empty, graph) shouldBe empty
    }
  }
