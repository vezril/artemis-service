package me.cference.artemis.search

import me.cference.artemis.domain.{Tag, TagGraph}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class AliasResolverSpec extends AnyWordSpec with Matchers:

  import Polarity.*

  private def tag(s: String): Tag = Tag.from(s).toOption.getOrElse(sys.error(s"bad tag: $s"))

  private def graphOf(pairs: (String, String)*): TagGraph =
    TagGraph(aliases = pairs.map((a, c) => tag(a) -> tag(c)).toMap, implications = Map.empty)

  private def resolve(query: SearchQuery, graph: TagGraph): Seq[Term] =
    AliasResolver.resolve(query, graph).terms

  "AliasResolver.resolve" should {

    "rewrite an aliased include tag to its consequent" in {
      val graph = graphOf("catgirl" -> "cat_girl")
      resolve(SearchQuery(Seq(TagTerm(Include, TagPattern("catgirl")))), graph) shouldBe
        Seq(TagTerm(Include, TagPattern("cat_girl")))
    }

    "preserve exclude polarity when rewriting a negated alias" in {
      val graph = graphOf("catgirl" -> "cat_girl")
      resolve(
        SearchQuery(
          Seq(TagTerm(Include, TagPattern("1girl")), TagTerm(Exclude, TagPattern("catgirl")))
        ),
        graph
      ) shouldBe Seq(
        TagTerm(Include, TagPattern("1girl")),
        TagTerm(Exclude, TagPattern("cat_girl"))
      )
    }

    "preserve OR polarity when rewriting a ~ alias" in {
      val graph = graphOf("catgirl" -> "cat_girl")
      resolve(SearchQuery(Seq(TagTerm(Or, TagPattern("catgirl")))), graph) shouldBe
        Seq(TagTerm(Or, TagPattern("cat_girl")))
    }

    "follow an alias chain a -> b -> c to the terminal consequent" in {
      val graph = graphOf("a" -> "b", "b" -> "c")
      resolve(SearchQuery(Seq(TagTerm(Include, TagPattern("a")))), graph) shouldBe
        Seq(TagTerm(Include, TagPattern("c")))
    }

    "leave a non-aliased tag unchanged" in {
      val graph = graphOf("catgirl" -> "cat_girl")
      resolve(SearchQuery(Seq(TagTerm(Include, TagPattern("1girl")))), graph) shouldBe
        Seq(TagTerm(Include, TagPattern("1girl")))
    }

    "not rewrite a wildcard pattern even if its stem looks aliased" in {
      val graph = graphOf("cat" -> "feline")
      resolve(SearchQuery(Seq(TagTerm(Include, TagPattern("cat_*")))), graph) shouldBe
        Seq(TagTerm(Include, TagPattern("cat_*")))
    }

    "leave metatags untouched" in {
      val graph = graphOf("catgirl" -> "cat_girl")
      val meta = MetaTerm(Include, "rating", MetaValue.Scalar("s"))
      resolve(SearchQuery(Seq(meta)), graph) shouldBe Seq(meta)
    }
  }
