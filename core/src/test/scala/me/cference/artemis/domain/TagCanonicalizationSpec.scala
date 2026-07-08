package me.cference.artemis.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class TagCanonicalizationSpec extends AnyWordSpec with Matchers:

  private def t(s: String): Tag = Tag.unsafe(s)

  "TagCanonicalization.canonicalize" should {

    "rewrite an antecedent alias to its consequent" in {
      val graph = TagGraph(aliases = Map(t("catgirl") -> t("cat_girl")), implications = Map.empty)
      TagCanonicalization.canonicalize(Set(t("catgirl")), graph) shouldBe Set(t("cat_girl"))
    }

    "resolve an alias chain to its terminal consequent" in {
      val graph = TagGraph(
        aliases = Map(t("a") -> t("b"), t("b") -> t("c")),
        implications = Map.empty
      )
      TagCanonicalization.canonicalize(Set(t("a")), graph) shouldBe Set(t("c"))
    }

    "expand implications transitively" in {
      val graph = TagGraph(
        aliases = Map.empty,
        implications = Map(
          t("cat_girl") -> Set(t("animal_ears")),
          t("animal_ears") -> Set(t("animal_humanoid"))
        )
      )
      TagCanonicalization.canonicalize(Set(t("cat_girl")), graph) shouldBe
        Set(t("cat_girl"), t("animal_ears"), t("animal_humanoid"))
    }

    "terminate on an implication cycle with no duplicates" in {
      val graph = TagGraph(
        aliases = Map.empty,
        implications = Map(t("x") -> Set(t("y")), t("y") -> Set(t("x")))
      )
      TagCanonicalization.canonicalize(Set(t("x")), graph) shouldBe Set(t("x"), t("y"))
    }

    "apply aliases before implications so implications key off canonical names" in {
      val graph = TagGraph(
        aliases = Map(t("catgirl") -> t("cat_girl")),
        implications = Map(t("cat_girl") -> Set(t("animal_ears")))
      )
      TagCanonicalization.canonicalize(Set(t("catgirl")), graph) shouldBe
        Set(t("cat_girl"), t("animal_ears"))
    }

    "collapse duplicates arising from alias plus explicit tag" in {
      val graph = TagGraph(
        aliases = Map(t("catgirl") -> t("cat_girl")),
        implications = Map.empty
      )
      TagCanonicalization.canonicalize(Set(t("catgirl"), t("cat_girl")), graph) shouldBe
        Set(t("cat_girl"))
    }

    "leave a tag with no alias or implication unchanged" in {
      TagCanonicalization.canonicalize(Set(t("solo")), TagGraph.empty) shouldBe Set(t("solo"))
    }
  }
