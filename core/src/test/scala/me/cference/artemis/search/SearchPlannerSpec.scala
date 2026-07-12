package me.cference.artemis.search

import me.cference.artemis.domain.{Tag, TagGraph}
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

final class SearchPlannerSpec extends AnyWordSpec with Matchers with ScalaFutures with EitherValues:

  import Polarity.*

  private def tag(s: String): Tag = Tag.from(s).toOption.getOrElse(sys.error(s"bad tag: $s"))
  private def tagSet(ss: String*): Set[Tag] = ss.map(tag).toSet

  /** A fake expander driven by an explicit pattern -> tags map; listed patterns are "too broad". */
  final private class FakeExpander(
      mapping: Map[String, Set[Tag]] = Map.empty,
      tooBroad: Set[String] = Set.empty
  ) extends WildcardExpander:
    def expand(pattern: String): Future[Either[WildcardTooBroad, Set[Tag]]] =
      if tooBroad.contains(pattern) then Future.successful(Left(WildcardTooBroad(pattern)))
      else Future.successful(Right(mapping.getOrElse(pattern, Set.empty)))

  private def plan(
      terms: Seq[Term],
      graph: TagGraph = TagGraph.empty,
      expander: WildcardExpander = FakeExpander()
  ): Either[PlanError, QueryPlan] =
    SearchPlanner.plan(SearchQuery(terms), graph, expander).futureValue

  "SearchPlanner.plan" should {

    "AND two positional include tags into includes" in {
      val p = plan(
        Seq(TagTerm(Include, TagPattern("1girl")), TagTerm(Include, TagPattern("cat_ears")))
      ).value
      p.includes shouldBe tagSet("1girl", "cat_ears")
      p.excludes shouldBe empty
      p.orGroups shouldBe empty
    }

    "route a negated tag into excludes while keeping the positive include" in {
      val p = plan(
        Seq(TagTerm(Include, TagPattern("1girl")), TagTerm(Exclude, TagPattern("monochrome")))
      ).value
      p.includes shouldBe tagSet("1girl")
      p.excludes shouldBe tagSet("monochrome")
    }

    "fold ~ terms into a single flat OR group, ANDed with a plain include" in {
      val p = plan(
        Seq(
          TagTerm(Or, TagPattern("cat_ears")),
          TagTerm(Or, TagPattern("dog_ears")),
          TagTerm(Include, TagPattern("animal"))
        )
      ).value
      p.includes shouldBe tagSet("animal")
      p.orGroups shouldBe Seq(tagSet("cat_ears", "dog_ears"))
    }

    "expand a positive wildcard into its own OR group" in {
      val expander = FakeExpander(Map("cat_*" -> tagSet("cat_ears", "cat_girl")))
      val p = plan(
        Seq(TagTerm(Include, TagPattern("cat_*")), TagTerm(Include, TagPattern("1girl"))),
        expander = expander
      ).value
      p.includes shouldBe tagSet("1girl")
      p.orGroups shouldBe Seq(tagSet("cat_ears", "cat_girl"))
    }

    "union a negated wildcard's expansion into excludes" in {
      val expander = FakeExpander(Map("cat_*" -> tagSet("cat_ears", "cat_girl")))
      val p = plan(
        Seq(TagTerm(Include, TagPattern("1girl")), TagTerm(Exclude, TagPattern("cat_*"))),
        expander = expander
      ).value
      p.includes shouldBe tagSet("1girl")
      p.excludes shouldBe tagSet("cat_ears", "cat_girl")
    }

    "fail the whole plan when a wildcard is over-broad" in {
      val expander = FakeExpander(tooBroad = Set("a*"))
      val result = plan(
        Seq(TagTerm(Include, TagPattern("1girl")), TagTerm(Include, TagPattern("a*"))),
        expander = expander
      )
      result.left.value shouldBe WildcardTooBroad("a*")
    }

    "apply alias resolution before planning so an antecedent lands as its consequent" in {
      val graph =
        TagGraph(aliases = Map(tag("catgirl") -> tag("cat_girl")), implications = Map.empty)
      val p = plan(Seq(TagTerm(Include, TagPattern("catgirl"))), graph = graph).value
      p.includes shouldBe tagSet("cat_girl")
    }

    "carry metatag predicates and extract order/limit" in {
      val p = plan(
        Seq(
          MetaTerm(Include, "score", MetaValue.Compare(Cmp.Gt, "10")),
          MetaTerm(Include, "order", MetaValue.Scalar("score")),
          MetaTerm(Include, "limit", MetaValue.Scalar("50"))
        )
      ).value
      p.predicates shouldBe Seq(MetaTerm(Include, "score", MetaValue.Compare(Cmp.Gt, "10")))
      p.order shouldBe Some("score")
      p.limit shouldBe Some(50)
    }

    "keep an empty positive-wildcard expansion as an empty OR group (matches nothing)" in {
      // `cat_*` expands to zero live tags → an unsatisfiable group, KEPT (not dropped) so §4
      // renders it as FALSE rather than silently ignoring the constraint.
      val p = plan(
        Seq(TagTerm(Include, TagPattern("1girl")), TagTerm(Include, TagPattern("cat_*")))
        // cat_* is unmapped in the fake → Right(Set.empty)
      ).value
      p.includes shouldBe tagSet("1girl")
      p.orGroups shouldBe Seq(Set.empty[Tag])
    }

    "ignore a non-numeric limit, falling back to the default page size" in {
      val p = plan(
        Seq(
          TagTerm(Include, TagPattern("1girl")),
          MetaTerm(Include, "limit", MetaValue.Scalar("abc"))
        )
      ).value
      p.limit shouldBe None
    }

    "treat a negated order: as a predicate, not a result control" in {
      val p = plan(
        Seq(
          TagTerm(Include, TagPattern("1girl")),
          MetaTerm(Exclude, "order", MetaValue.Scalar("score"))
        )
      ).value
      p.order shouldBe None
      p.predicates.map(_.name) should contain("order")
    }
  }
