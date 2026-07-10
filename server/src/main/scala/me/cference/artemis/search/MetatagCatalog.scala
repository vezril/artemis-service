package me.cference.artemis.search

/**
 * The grammar-aware, static metatag-value catalog behind context-aware autocomplete (catalog-api
 * "autocomplete is context-aware", search-dsl design "Autocomplete"). Mid-metatag (`rating:`,
 * `order:`, `is:`), the autocomplete suggests the metatag's enum values rather than tag names —
 * pure knowledge of the DSL grammar, so it needs no database.
 *
 * The enum sets mirror what [[SqlCompiler]] actually accepts today: `rating` g|s|q|e, the supported
 * `order:` names, and `is:` video|animated. `status:` is a recognized-but-stubbed metatag
 * (moderation is a later milestone), so it yields no suggestions.
 */
object MetatagCatalog:

  /** The `order:` names the read model can actually sort by (mirrors `SqlCompiler`). */
  private val Orders: Seq[String] =
    Seq("id", "score", "favcount", "duration", "mpixels", "date", "random")

  private val Enums: Map[String, Seq[String]] = Map(
    "rating" -> Seq("g", "s", "q", "e"),
    "order" -> Orders,
    "is" -> Seq("video", "animated"),
    "status" -> Seq.empty // STUB until moderation/multi-user
  )

  /**
   * Suggest metatag values for a partial `name:value` query. With no `:` (a bare tag, which is the
   * tag-autocomplete context, not this one) yields nothing; for a known metatag it returns the enum
   * values whose text starts with whatever value fragment has been typed so far; an unknown metatag
   * yields nothing.
   */
  def suggest(q: String): Seq[String] =
    q.indexOf(':') match
      case -1 => Seq.empty
      case idx =>
        val name = q.take(idx).toLowerCase
        val valuePrefix = q.drop(idx + 1)
        Enums.getOrElse(name, Seq.empty).filter(_.startsWith(valuePrefix))
