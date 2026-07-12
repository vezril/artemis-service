package me.cference.artemis.search

/**
 * Errors that abort compilation of a [[QueryPlan]] into SQL (search-dsl tasks 4.2-4.3). Total,
 * errors-as-values: [[SqlCompiler.compile]] returns `Left(CompileError)` rather than throwing, and
 * the executor surfaces it as a `Left` result — a bad metatag never runs a degraded query.
 */
sealed trait CompileError:
  def message: String

object CompileError:

  /**
   * A catalog metatag that the current `posts` read model cannot back — it needs a projection
   * column or join that does not exist yet (`filesize`, `fps`, `audio`, `pool`, `ordpool`), or an
   * unknown name. Named so the caller can guide the user; deferred, never silently dropped.
   */
  final case class UnsupportedMetatag(name: String) extends CompileError:
    def message: String = s"metatag '$name' is not supported by the current read model"

  /** A metatag value that does not parse to the column's type, or is out of the allowed enum. */
  final case class InvalidMetaValue(name: String, raw: String, reason: String) extends CompileError:
    def message: String = s"metatag '$name' value '$raw' is invalid: $reason"

  /** An `order:` selecting a column the read model cannot sort on (e.g. deferred `filesize`). */
  final case class UnsupportedOrder(name: String) extends CompileError:
    def message: String = s"order '$name' is not supported"

  /** A pagination cursor that is malformed or carries a value that does not parse. */
  final case class InvalidCursor(reason: String) extends CompileError:
    def message: String = s"invalid cursor: $reason"
