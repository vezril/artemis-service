package me.cference.artemis.domain

/**
 * The fixed set of tag categories (tag-model spec). Each carries a stable numeric `id` matching
 * Danbooru's numbering — `2` is intentionally unused so imported Danbooru data lines up — and, for
 * the non-general categories, a search `prefix` that namespaces query terms (e.g. `character:cat`).
 *
 * Every tag carries exactly one category; `General` is the default and has no search prefix.
 */
enum TagCategory(val id: Int, val prefix: Option[String]):
  case General extends TagCategory(0, None)
  case Artist extends TagCategory(1, Some("artist"))
  case Copyright extends TagCategory(3, Some("copyright"))
  case Character extends TagCategory(4, Some("character"))
  case Meta extends TagCategory(5, Some("meta"))

object TagCategory:

  /** Rehydrate a category from its stored id; an id outside the fixed set is a typed rejection. */
  def fromId(id: Int): Either[DomainError, TagCategory] =
    values
      .find(_.id == id)
      .toRight(DomainError.InvalidTagCategory(s"unknown tag category id: $id"))

  /**
   * Resolve a search-namespace prefix (e.g. `character`) to its category; `None` if unrecognized.
   */
  def fromPrefix(prefix: String): Option[TagCategory] =
    values.find(_.prefix.contains(prefix))
