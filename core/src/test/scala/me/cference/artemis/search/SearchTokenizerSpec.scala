package me.cference.artemis.search

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class SearchTokenizerSpec extends AnyWordSpec with Matchers:

  "SearchTokenizer.tokenize" should {

    "split a plain query on whitespace" in {
      SearchTokenizer.tokenize("1girl cat_ears") shouldBe Seq("1girl", "cat_ears")
    }

    "keep a quoted phrase as a single token, stripping the quotes" in {
      SearchTokenizer.tokenize("""source:"http://a b"""") shouldBe Seq("source:http://a b")
    }

    "preserve inner whitespace of a quoted tag" in {
      SearchTokenizer.tokenize(""""cat ears" 1girl""") shouldBe Seq("cat ears", "1girl")
    }

    "collapse leading, trailing, and repeated spaces" in {
      SearchTokenizer.tokenize("   1girl    cat_ears   ") shouldBe Seq("1girl", "cat_ears")
    }

    "return no terms for an empty query" in {
      SearchTokenizer.tokenize("") shouldBe Seq.empty
    }

    "return no terms for a blank query" in {
      SearchTokenizer.tokenize("     ") shouldBe Seq.empty
    }

    "carry term punctuation through untouched" in {
      SearchTokenizer.tokenize("~cat_ears -monochrome score:>10 id:100..200 cat_*") shouldBe
        Seq("~cat_ears", "-monochrome", "score:>10", "id:100..200", "cat_*")
    }
  }
