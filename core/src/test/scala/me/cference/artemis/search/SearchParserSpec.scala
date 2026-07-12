package me.cference.artemis.search

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class SearchParserSpec extends AnyWordSpec with Matchers with EitherValues:

  import ParseError.*
  import Polarity.*

  private def parse(q: String) = SearchParser.parse(q)

  "SearchParser.parse" should {

    "reject an empty query" in {
      parse("").left.value shouldBe EmptyQuery
    }

    "reject a blank query" in {
      parse("   ").left.value shouldBe EmptyQuery
    }

    "AND two positional tags as include terms" in {
      parse("1girl cat_ears").value.terms shouldBe Seq(
        TagTerm(Include, TagPattern("1girl")),
        TagTerm(Include, TagPattern("cat_ears"))
      )
    }

    "treat order and repetition of positional tags as irrelevant to the term set" in {
      val a = parse("1girl cat_ears").value.terms.toSet
      val b = parse("cat_ears 1girl").value.terms.toSet
      val c = parse("1girl cat_ears 1girl").value.terms.toSet
      a shouldBe b
      b shouldBe c
    }

    "negate a tag term prefixed with -" in {
      parse("1girl -monochrome").value.terms shouldBe Seq(
        TagTerm(Include, TagPattern("1girl")),
        TagTerm(Exclude, TagPattern("monochrome"))
      )
    }

    "negate a metatag term prefixed with -" in {
      parse("1girl -rating:e").value.terms shouldBe Seq(
        TagTerm(Include, TagPattern("1girl")),
        MetaTerm(Exclude, "rating", MetaValue.Scalar("e"))
      )
    }

    "join all ~ terms into a flat OR set alongside a plain AND term" in {
      parse("~cat_ears ~dog_ears animal").value.terms shouldBe Seq(
        TagTerm(Or, TagPattern("cat_ears")),
        TagTerm(Or, TagPattern("dog_ears")),
        TagTerm(Include, TagPattern("animal"))
      )
    }

    "keep a single ~ term as a one-member OR set" in {
      parse("~cat_ears 1girl").value.terms shouldBe Seq(
        TagTerm(Or, TagPattern("cat_ears")),
        TagTerm(Include, TagPattern("1girl"))
      )
    }

    "recognize a wildcard in a tag pattern without expanding it" in {
      val terms = parse("1girl cat_*").value.terms
      terms shouldBe Seq(
        TagTerm(Include, TagPattern("1girl")),
        TagTerm(Include, TagPattern("cat_*"))
      )
      TagPattern("cat_*").hasWildcard shouldBe true
      TagPattern("cat_ears").hasWildcard shouldBe false
    }

    "parse a comparison metatag with > as Compare(Gt)" in {
      parse("score:>10").value.terms shouldBe Seq(
        MetaTerm(Include, "score", MetaValue.Compare(Cmp.Gt, "10"))
      )
    }

    "parse the >=, <=, and < comparison operators" in {
      parse("score:>=5").value.terms shouldBe
        Seq(MetaTerm(Include, "score", MetaValue.Compare(Cmp.Ge, "5")))
      parse("score:<=5").value.terms shouldBe
        Seq(MetaTerm(Include, "score", MetaValue.Compare(Cmp.Le, "5")))
      parse("score:<5").value.terms shouldBe
        Seq(MetaTerm(Include, "score", MetaValue.Compare(Cmp.Lt, "5")))
    }

    "parse a closed range a..b into both bounds" in {
      parse("id:100..200").value.terms shouldBe Seq(
        MetaTerm(Include, "id", MetaValue.RangeVal(Some("100"), Some("200")))
      )
    }

    "parse an open-ended range a.. into a low bound only" in {
      parse("date:2024-01-01..").value.terms shouldBe Seq(
        MetaTerm(Include, "date", MetaValue.RangeVal(Some("2024-01-01"), None))
      )
    }

    "parse an open-started range ..b into a high bound only" in {
      parse("id:..200").value.terms shouldBe Seq(
        MetaTerm(Include, "id", MetaValue.RangeVal(None, Some("200")))
      )
    }

    "parse a duration comparison metatag" in {
      parse("duration:>30").value.terms shouldBe Seq(
        MetaTerm(Include, "duration", MetaValue.Compare(Cmp.Gt, "30"))
      )
    }

    "parse a plain enum metatag value as a scalar" in {
      parse("rating:s").value.terms shouldBe Seq(
        MetaTerm(Include, "rating", MetaValue.Scalar("s"))
      )
    }

    "keep a quoted metatag value with spaces intact" in {
      parse("""source:"http://a b"""").value.terms shouldBe Seq(
        MetaTerm(Include, "source", MetaValue.Scalar("http://a b"))
      )
    }

    "reject a metatag with an empty name" in {
      parse(":foo").left.value shouldBe a[MalformedMetatag]
    }

    "reject a metatag with an empty value" in {
      parse("rating:").left.value shouldBe a[MalformedMetatag]
    }

    "reject a range with neither bound" in {
      parse("id:..").left.value shouldBe a[MalformedRange]
    }

    "reject a comparison with no scalar" in {
      parse("score:>").left.value shouldBe a[MalformedMetatag]
    }

    "reject a range with more than two bounds" in {
      parse("id:1..2..3").left.value shouldBe a[MalformedRange]
    }

    "reject a range whose bound starts with a comparator" in {
      parse("score:>1..5").left.value shouldBe a[MalformedRange]
    }

    "reject a bare ~ as an empty term" in {
      parse("~").left.value shouldBe ParseError.EmptyTerm
    }

    "reject a trailing - as an empty term rather than a misleading anchor error" in {
      parse("1girl -").left.value shouldBe ParseError.EmptyTerm
    }
  }

  "SearchParser.parse guardrails" should {

    "reject a query of only negated terms with NoPositiveAnchor" in {
      parse("-monochrome").left.value shouldBe NoPositiveAnchor
    }

    "reject a query of several negated terms with NoPositiveAnchor" in {
      parse("-monochrome -rating:e").left.value shouldBe NoPositiveAnchor
    }

    "accept a query anchored by a positive tag alongside a negation" in {
      parse("1girl -monochrome").value.terms should have size 2
    }

    "accept a query anchored by a metatag alongside a negation" in {
      parse("rating:s -monochrome").value.terms should have size 2
    }

    "accept a query anchored only by an OR term" in {
      parse("~cat_ears -monochrome").value.terms should have size 2
    }

    "accept exactly the maximum number of positive tags" in {
      val q = (1 to SearchParser.MaxPositiveTags).map(i => s"tag$i").mkString(" ")
      parse(q).value.terms should have size SearchParser.MaxPositiveTags
    }

    "reject more than the maximum number of positive tags" in {
      val q = (1 to SearchParser.MaxPositiveTags + 1).map(i => s"tag$i").mkString(" ")
      parse(q).left.value shouldBe a[TooManyPositiveTags]
    }

    "not count excluded or OR tags toward the positive-tag ceiling" in {
      val excludes = (1 to 50).map(i => s"-tag$i").mkString(" ")
      parse(s"anchor $excludes").value.terms should have size 51
    }
  }
