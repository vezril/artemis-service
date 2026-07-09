package me.cference.artemis.search

/**
 * The parsed search-query AST. This is the pure front-end representation produced by
 * [[SearchParser]]: a flat list of terms, each carrying its polarity (include / exclude / OR) and
 * kind (tag pattern vs. metatag). Alias resolution, wildcard expansion, SQL compilation, and the
 * query plan (`includes`/`excludes`/`orSet`/predicates) are deliberately NOT modelled here — they
 * belong to later stages of the pipeline.
 */

/** Comparison operator for a metatag value. */
enum Cmp:
  case Gt, Lt, Ge, Le

/** The value side of a metatag, in one of the DSL's value shapes. */
enum MetaValue:
  /** A bare value: `rating:s`, `source:http://…`. */
  case Scalar(raw: String)

  /** A comparison: `score:>10`, `duration:>=30`. */
  case Compare(cmp: Cmp, raw: String)

  /** A range: `a..b` (both), `a..` (lo only), `..b` (hi only). */
  case RangeVal(lo: Option[String], hi: Option[String])

/** A tag term's pattern; `*` marks a wildcard (recognized here, expanded later). */
final case class TagPattern(text: String):
  def hasWildcard: Boolean = text.contains('*')

/** A term's polarity: plain include, `-` exclude, or `~` flat-OR member. */
enum Polarity:
  case Include, Exclude, Or

/** A single parsed query term. */
sealed trait Term:
  def polarity: Polarity

/** A tag (or wildcard) term, e.g. `1girl`, `-monochrome`, `~cat_ears`, `cat_*`. */
final case class TagTerm(polarity: Polarity, pattern: TagPattern) extends Term

/** A metatag term, e.g. `rating:s`, `score:>10`, `-rating:e`, `id:100..200`. */
final case class MetaTerm(polarity: Polarity, name: String, value: MetaValue) extends Term

/** The parsed query: the ordered term list. The query *plan* is a later concern. */
final case class SearchQuery(terms: Seq[Term])
