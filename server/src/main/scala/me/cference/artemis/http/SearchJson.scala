package me.cference.artemis.http

import me.cference.artemis.projection.{FacetEntry, PostRow, TagSuggestion}
import spray.json.*
import spray.json.DefaultJsonProtocol.*

/**
 * DTOs + spray-json formats for the DSL read surface (`GET /posts`, `/posts/facets`,
 * `/tags/autocomplete`). Mirrors [[CatalogJson]]'s flat, hand-shaped style. Read-model rows
 * ([[PostRow]], [[FacetEntry]], [[TagSuggestion]]) are projected to wire DTOs here; the
 * autocomplete/facet field names (`post_count`, `alias_of`) match the Muses contract's snake_case.
 */

// --- GET /posts ---

/** A search-result post summary — the gallery-card fields, projected from a [[PostRow]]. */
final case class PostSummary(
    id: String,
    status: String,
    tags: List[String],
    rating: Option[String],
    score: Int,
    favCount: Int,
    width: Option[Int],
    height: Option[Int],
    duration: Option[Long],
    parent: Option[String],
    duplicateOf: Option[String],
    createdAt: String
)

final case class SearchResponse(posts: List[PostSummary], nextCursor: Option[String])

// --- GET /posts/facets ---

final case class FacetTag(name: String, count: Int)
final case class FacetCategory(category: Int, tags: List[FacetTag])
final case class FacetsResponse(facets: List[FacetCategory])

// --- GET /tags/autocomplete (tag context) ---

final case class TagSuggestionResponse(
    name: String,
    category: Int,
    post_count: Int,
    alias_of: Option[String]
)

object SearchJson:

  given RootJsonFormat[PostSummary] = jsonFormat12(PostSummary.apply)
  given RootJsonFormat[SearchResponse] = jsonFormat2(SearchResponse.apply)
  given RootJsonFormat[FacetTag] = jsonFormat2(FacetTag.apply)
  given RootJsonFormat[FacetCategory] = jsonFormat2(FacetCategory.apply)
  given RootJsonFormat[FacetsResponse] = jsonFormat1(FacetsResponse.apply)
  given RootJsonFormat[TagSuggestionResponse] = jsonFormat4(TagSuggestionResponse.apply)

  // Explicit array formats so the routes can marshal a bare JSON array without pulling in
  // DefaultJsonProtocol's ambiguous Seq/immSeq/List collection formats at the call site.
  given suggestionListFormat: RootJsonFormat[List[TagSuggestionResponse]] =
    listFormat[TagSuggestionResponse]
  given stringListFormat: RootJsonFormat[List[String]] = listFormat[String]

  /** Project a read-model [[PostRow]] into the wire summary. */
  def summaryOf(r: PostRow): PostSummary =
    PostSummary(
      id = r.id,
      status = r.status,
      tags = r.tags.toList,
      rating = r.rating,
      score = r.score,
      favCount = r.favCount,
      width = r.width,
      height = r.height,
      duration = r.duration,
      parent = r.parentId,
      duplicateOf = r.duplicateOf,
      createdAt = r.createdAt.toString
    )

  def searchResponse(rows: Seq[PostRow], nextCursor: Option[String]): SearchResponse =
    SearchResponse(rows.map(summaryOf).toList, nextCursor)

  /**
   * Group flat facet entries by category (categories ascending), preserving the count-descending
   * tag order within each category as produced by the aggregation.
   */
  def facetsResponse(entries: Seq[FacetEntry]): FacetsResponse =
    val grouped = entries
      .groupBy(_.category)
      .toSeq
      .sortBy(_._1)
      .map { (category, es) =>
        FacetCategory(category, es.map(e => FacetTag(e.name, e.count)).toList)
      }
    FacetsResponse(grouped.toList)

  def suggestionOf(s: TagSuggestion): TagSuggestionResponse =
    TagSuggestionResponse(s.name, s.category, s.postCount, s.aliasOf)
