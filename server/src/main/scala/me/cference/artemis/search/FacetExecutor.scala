package me.cference.artemis.search

import me.cference.artemis.projection.{FacetEntry, ReadModelRepository}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Aggregates the tag facets for a compiled [[QueryPlan]] (catalog-api "Tag facets for a query",
 * task 5.2): over the SAME matching post set as [[SearchExecutor]] (soft-deleted rows excluded by
 * the shared status default), the tags occurring across those posts, each with its category and a
 * count of how many matches carry it.
 *
 * The plan→WHERE is reused verbatim from [[SqlCompiler.compileWhere]] (not re-derived), so the
 * facet set and the search result set can never drift. The aggregation `unnest`s each matching
 * post's `tags[]`, LEFT JOINs the `tags` table for the category (missing → `0` via COALESCE), and
 * groups by `(tag, category)`. Every user value stays a bind param (injection-safe); a compile
 * failure (bad metatag) surfaces as `Left(CompileError)` — never a thrown exception or a degraded
 * query.
 */
final class FacetExecutor(repo: ReadModelRepository)(using ec: ExecutionContext):

  def facets(plan: QueryPlan): Future[Either[CompileError, Seq[FacetEntry]]] =
    SqlCompiler.compileWhere(plan) match
      case Left(err) => Future.successful(Left(err))
      case Right(where) =>
        val sql =
          // COUNT(DISTINCT posts.id): the count is "how many matching posts carry this tag", robust
          // to any duplicate entry in a post's `tags[]` array (the projection dedupes, but the array
          // has no uniqueness constraint).
          s"""SELECT tag AS name, COALESCE(t.category, 0) AS category, COUNT(DISTINCT posts.id) AS cnt
             |FROM posts, unnest(tags) AS tag
             |LEFT JOIN tags t ON t.name = tag
             |WHERE ${where.sql}
             |GROUP BY tag, COALESCE(t.category, 0)
             |ORDER BY cnt DESC, name ASC""".stripMargin
        repo.selectFacets(sql, stmt => SqlParam.bindAll(stmt, where.params)).map(Right(_))
