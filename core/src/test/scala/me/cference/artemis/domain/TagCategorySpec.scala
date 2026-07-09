package me.cference.artemis.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TagCategorySpec extends AnyWordSpec with Matchers:

  "TagCategory ids" should {

    "assign the fixed Danbooru-compatible numbering" in {
      TagCategory.General.id shouldBe 0
      TagCategory.Artist.id shouldBe 1
      TagCategory.Copyright.id shouldBe 3
      TagCategory.Character.id shouldBe 4
      TagCategory.Meta.id shouldBe 5
    }
  }

  "TagCategory.fromId" should {

    "map each valid id to its category" in {
      TagCategory.fromId(0) shouldBe Right(TagCategory.General)
      TagCategory.fromId(1) shouldBe Right(TagCategory.Artist)
      TagCategory.fromId(3) shouldBe Right(TagCategory.Copyright)
      TagCategory.fromId(4) shouldBe Right(TagCategory.Character)
      TagCategory.fromId(5) shouldBe Right(TagCategory.Meta)
    }

    "reject the intentionally-skipped id 2 with a typed error" in {
      TagCategory.fromId(2) shouldBe a[Left[?, ?]]
      TagCategory.fromId(2).left.map(_.getClass) shouldBe
        Left(classOf[DomainError.InvalidTagCategory])
    }

    "reject an out-of-range id with a typed error" in {
      TagCategory.fromId(9) shouldBe a[Left[?, ?]]
      TagCategory.fromId(9).swap.toOption.get shouldBe a[DomainError.InvalidTagCategory]
    }
  }

  "TagCategory search prefixes" should {

    "expose the search namespace for the prefixed categories and none for general" in {
      TagCategory.Artist.prefix shouldBe Some("artist")
      TagCategory.Copyright.prefix shouldBe Some("copyright")
      TagCategory.Character.prefix shouldBe Some("character")
      TagCategory.Meta.prefix shouldBe Some("meta")
      TagCategory.General.prefix shouldBe None
    }

    "resolve a known prefix back to its category" in {
      TagCategory.fromPrefix("character") shouldBe Some(TagCategory.Character)
      TagCategory.fromPrefix("artist") shouldBe Some(TagCategory.Artist)
    }

    "not resolve an unknown or empty prefix" in {
      TagCategory.fromPrefix("general") shouldBe None
      TagCategory.fromPrefix("nope") shouldBe None
    }
  }
