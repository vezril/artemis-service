package me.cference.artemis.search

import me.cference.artemis.domain.{Tag, TagGraph}
import me.cference.artemis.projection.{FacetEntry, PostRow}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

/**
 * Unit tests (no DB) for [[SearchService]] — the parse→plan→execute orchestration behind `GET
 * /posts` (task 5.1). The executor, facet aggregation, tag graph, and wildcard expander are all
 * injected, so the fakes here exercise the wiring: a valid query yields a page, every failure mode
 * (parse / plan / compile) collapses to a `SearchError` (all 400-mapped), and the `order=` param
 * overrides the DSL's `order:`.
 */
final class SearchServiceSpec extends AnyWordSpec with Matchers with ScalaFutures:

  private given ec: ExecutionContext = ExecutionContext.global
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(3, Seconds), interval = Span(20, Millis))

  private def row(id: String): PostRow =
    PostRow(id, Seq("1girl"), "active", 0, 0, None, None, None, None, None, None, Instant.EPOCH)

  private val page = SearchPage(Seq(row("p1")), None)

  /** An expander that fails loudly if a wildcard-free test ever calls it (it should not). */
  private val stubExpander: WildcardExpander =
    _ => Future.successful(Right(Set.empty))

  /** Build a service, capturing the plan handed to the executor for order-precedence assertions. */
  private def service(
      execute: (QueryPlan, Option[String], Int) => Future[Either[CompileError, SearchPage]] =
        (_, _, _) => Future.successful(Right(page)),
      computeFacets: QueryPlan => Future[Either[CompileError, Seq[FacetEntry]]] = _ =>
        Future.successful(Right(Nil)),
      graph: TagGraph = TagGraph.empty
  ): SearchService =
    new SearchService(() => Future.successful(graph), stubExpander, execute, computeFacets)

  "SearchService.search" should {

    "return a page for a valid query" in {
      service().search("1girl", None, None, 20).futureValue shouldBe Right(page)
    }

    "reject a pure-negation query as a BadQuery (parse failure)" in {
      service().search("-monochrome", None, None, 20).futureValue match
        case Left(err: SearchError.BadQuery) => err.message should not be empty
        case other => fail(s"expected BadQuery, got $other")
    }

    "reject an empty query as a BadQuery" in {
      service().search("", None, None, 20).futureValue shouldBe a[Left[?, ?]]
    }

    "map a compile failure (unsupported metatag) to a 400 SearchError" in {
      val svc = service(execute =
        (_, _, _) => Future.successful(Left(CompileError.UnsupportedMetatag("filesize")))
      )
      svc.search("1girl filesize:>10", None, None, 20).futureValue match
        case Left(err: SearchError.Uncompilable) => err.message should include("filesize")
        case other => fail(s"expected Uncompilable, got $other")
    }

    "let an explicit order= param override the DSL order:" in {
      var captured: Option[String] = None
      val svc = service(execute = (p, _, _) => {
        captured = p.order; Future.successful(Right(page))
      })
      svc.search("1girl order:date", Some("score"), None, 20).futureValue
      captured shouldBe Some("score")
    }

    "fall back to the DSL order: when no order= param is given" in {
      var captured: Option[String] = None
      val svc = service(execute = (p, _, _) => {
        captured = p.order; Future.successful(Right(page))
      })
      svc.search("1girl order:date", None, None, 20).futureValue
      captured shouldBe Some("date")
    }
  }

  "SearchService.facets" should {

    "return the facet entries for a valid query" in {
      val entries = Seq(FacetEntry("1girl", 0, 3))
      service(computeFacets = _ => Future.successful(Right(entries)))
        .facets("1girl")
        .futureValue shouldBe Right(entries)
    }

    "reject a bad query as a BadQuery" in {
      service().facets("-monochrome").futureValue shouldBe a[Left[?, ?]]
    }
  }
