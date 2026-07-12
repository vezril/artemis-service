package me.cference.artemis.search

import me.cference.artemis.domain.{Tag, TagGraph}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Compiles a parsed [[SearchQuery]] into a [[QueryPlan]] (search-dsl "Compilation pipeline"):
 * alias-resolve → wildcard-expand (capped) → assemble the plan. This is the last *pure-planning*
 * stage; SQL generation, execution, and keyset pagination (§4/§5) consume the plan and are NOT done
 * here — the plan merely carries the tag structure plus the metatag predicates/order/limit.
 *
 * Term folding:
 *   - include tag, no wildcard → `includes`.
 *   - include tag, wildcard → its expansion is one `orGroups` element.
 *   - exclude tag, no wildcard → `excludes`; wildcard → its expansion is unioned into `excludes`.
 *   - `~` (OR) tag → member of THE single flat OR group (non-wildcard tags and `~`-wildcard
 *     expansions are unioned into it); that group, if non-empty, is one `orGroups` element.
 *   - `order:` / `limit:` metatags → extracted to `order`/`limit`; every other metatag →
 *     `predicates`.
 *
 * A `Left` from the wildcard expander (over-broad pattern) fails the whole plan.
 */
object SearchPlanner:

  /** Working accumulator threaded through the term fold; `flatOr` is the single `~` OR set. */
  final private case class Acc(
      includes: Set[Tag],
      excludes: Set[Tag],
      wildcardGroups: Seq[Set[Tag]],
      flatOr: Set[Tag],
      predicates: Seq[MetaTerm],
      order: Option[String],
      limit: Option[Int]
  ):
    def toPlan: QueryPlan =
      val orGroups = wildcardGroups ++ Option.when(flatOr.nonEmpty)(flatOr)
      QueryPlan(includes, excludes, orGroups, predicates, order, limit)

  private object Acc:
    val empty: Acc = Acc(Set.empty, Set.empty, Seq.empty, Set.empty, Seq.empty, None, None)

  def plan(query: SearchQuery, graph: TagGraph, expander: WildcardExpander)(using
      ec: ExecutionContext
  ): Future[Either[PlanError, QueryPlan]] =
    val resolved = AliasResolver.resolve(query, graph)
    val start: Future[Either[PlanError, Acc]] = Future.successful(Right(Acc.empty))
    resolved.terms
      .foldLeft(start) { (accF, term) =>
        accF.flatMap {
          case Left(err) => Future.successful(Left(err))
          case Right(acc) => fold(acc, term, expander)
        }
      }
      .map(_.map(_.toPlan))

  private def fold(acc: Acc, term: Term, expander: WildcardExpander)(using
      ec: ExecutionContext
  ): Future[Either[PlanError, Acc]] =
    term match
      case TagTerm(Polarity.Include, pattern) if !pattern.hasWildcard =>
        pure(acc.copy(includes = acc.includes ++ tagOf(pattern)))

      case TagTerm(Polarity.Include, pattern) =>
        expander
          .expand(pattern.text)
          .map(_.map { tags =>
            acc.copy(wildcardGroups = acc.wildcardGroups :+ tags)
          })

      case TagTerm(Polarity.Exclude, pattern) if !pattern.hasWildcard =>
        pure(acc.copy(excludes = acc.excludes ++ tagOf(pattern)))

      case TagTerm(Polarity.Exclude, pattern) =>
        expander
          .expand(pattern.text)
          .map(_.map { tags =>
            acc.copy(excludes = acc.excludes ++ tags)
          })

      case TagTerm(Polarity.Or, pattern) if !pattern.hasWildcard =>
        pure(acc.copy(flatOr = acc.flatOr ++ tagOf(pattern)))

      case TagTerm(Polarity.Or, pattern) =>
        expander
          .expand(pattern.text)
          .map(_.map { tags =>
            acc.copy(flatOr = acc.flatOr ++ tags)
          })

      // Only a POSITIVE order:/limit: is a result control; a (parser-unlikely) negated one falls
      // through to `predicates`. A non-numeric `limit:` is ignored → `None` → default page size.
      case MetaTerm(Polarity.Include, "order", MetaValue.Scalar(raw)) =>
        pure(acc.copy(order = Some(raw)))

      case MetaTerm(Polarity.Include, "limit", MetaValue.Scalar(raw)) =>
        pure(raw.toIntOption.fold(acc)(n => acc.copy(limit = Some(n))))

      case m: MetaTerm =>
        pure(acc.copy(predicates = acc.predicates :+ m))

  /**
   * A resolved, non-wildcard pattern is canonical text; lift it to a `Tag`, skipping the
   * (unexpected) invalid case.
   */
  private def tagOf(pattern: TagPattern): Set[Tag] =
    Tag.from(pattern.text).toOption.toSet

  private def pure(acc: Acc): Future[Either[PlanError, Acc]] =
    Future.successful(Right(acc))
