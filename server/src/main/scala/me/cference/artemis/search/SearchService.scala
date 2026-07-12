package me.cference.artemis.search

import me.cference.artemis.domain.TagGraph
import me.cference.artemis.projection.FacetEntry

import scala.concurrent.{ExecutionContext, Future}

/**
 * Orchestrates the full catalog-search pipeline for the HTTP layer (task 5.1): parse the raw DSL,
 * plan it against the tag graph + wildcard expander, then execute (a page) or aggregate (facets).
 * Every stage failure collapses to a [[SearchError]] the routes map to `400` — a search failure is
 * a clean bad request, never a 500.
 *
 * The dependencies are injected as plain functions so the service is DB-free and fully fakeable:
 *   - `loadGraph` supplies the [[TagGraph]] snapshot; production passes `() =>
 *     tagGraphRepo.loadGraph()`. A cached/periodically-refreshed snapshot (avoiding a load per
 *     search) is roadmap M9 — deferred, and this thunk is exactly where it plugs in.
 *   - `expander` resolves `*` patterns (production: `ReadModelWildcardExpander`).
 *   - `execute` runs a compiled plan into a page (production: `SearchExecutor.search`).
 *   - `computeFacets` aggregates the tag facets (production: `FacetExecutor.facets`).
 *
 * Order precedence: an explicit `order=` request param OVERRIDES the DSL's `order:` metatag (the
 * param is the caller's deliberate UI choice, e.g. a column-header sort); absent the param, the
 * plan's own `order:` stands, else the executor's default (`date`).
 */
final class SearchService(
    loadGraph: () => Future[TagGraph],
    expander: WildcardExpander,
    execute: (QueryPlan, Option[String], Int) => Future[Either[CompileError, SearchPage]],
    computeFacets: QueryPlan => Future[Either[CompileError, Seq[FacetEntry]]]
)(using ec: ExecutionContext):

  def search(
      rawTags: String,
      order: Option[String],
      cursor: Option[String],
      pageSize: Int
  ): Future[Either[SearchError, SearchPage]] =
    planned(rawTags, order).flatMap {
      case Left(err) => Future.successful(Left(err))
      case Right(plan) =>
        execute(plan, cursor, pageSize).map(_.left.map(ce => SearchError.Uncompilable(ce.message)))
    }

  def facets(rawTags: String): Future[Either[SearchError, Seq[FacetEntry]]] =
    planned(rawTags, None).flatMap {
      case Left(err) => Future.successful(Left(err))
      case Right(plan) =>
        computeFacets(plan).map(_.left.map(ce => SearchError.Uncompilable(ce.message)))
    }

  /** Parse then plan `rawTags`, applying the `order=` override, as one shared front half. */
  private def planned(
      rawTags: String,
      order: Option[String]
  ): Future[Either[SearchError, QueryPlan]] =
    SearchParser.parse(rawTags) match
      case Left(pe) => Future.successful(Left(SearchError.BadQuery(pe.message)))
      case Right(query) =>
        loadGraph().flatMap { graph =>
          SearchPlanner.plan(query, graph, expander).map {
            case Left(planErr) => Left(SearchError.Unplannable(planErr.message))
            case Right(plan) => Right(applyOrderOverride(plan, order))
          }
        }

  private def applyOrderOverride(plan: QueryPlan, order: Option[String]): QueryPlan =
    order.map(_.trim).filter(_.nonEmpty) match
      case Some(explicit) => plan.copy(order = Some(explicit))
      case None => plan
