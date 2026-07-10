package me.cference.artemis.search

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Unit tests (pure, no DB) for [[MetatagCatalog]] — the grammar-aware, static metatag-value
 * suggestions behind `GET /tags/autocomplete?context=metatag` (catalog-api "autocomplete is
 * context-aware", task 5.3). Mid-metatag the autocomplete offers enum values rather than tag names.
 */
final class MetatagCatalogSpec extends AnyWordSpec with Matchers:

  "MetatagCatalog.suggest" should {

    "suggest the rating enum values for `rating:`" in {
      MetatagCatalog.suggest("rating:") shouldBe Seq("g", "s", "q", "e")
    }

    "suggest the order names for `order:`" in {
      MetatagCatalog.suggest("order:") should contain allOf ("score", "date", "random")
    }

    "suggest the video kinds for `is:`" in {
      MetatagCatalog.suggest("is:") shouldBe Seq("video", "animated")
    }

    "filter suggestions by the partial value already typed" in {
      MetatagCatalog.suggest("rating:e") shouldBe Seq("e")
    }

    "return no suggestions for the stubbed `status:` metatag" in {
      MetatagCatalog.suggest("status:") shouldBe empty
    }

    "return nothing for an unknown metatag name" in {
      MetatagCatalog.suggest("bogus:") shouldBe empty
    }

    "return nothing when there is no metatag prefix (a bare tag)" in {
      MetatagCatalog.suggest("1girl") shouldBe empty
    }
  }
