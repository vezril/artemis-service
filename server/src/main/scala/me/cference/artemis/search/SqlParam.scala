package me.cference.artemis.search

import io.r2dbc.spi.Statement

import java.time.{LocalDate, OffsetDateTime}

/**
 * A typed SQL bind parameter. The compiler emits one of these per user value so nothing is ever
 * string-interpolated into the SQL (injection-safe); the executor binds them positionally onto the
 * r2dbc [[Statement]] in `$1..$n` order. `TextArray` binds a Postgres `text[]` for the GIN
 * `@>`/`&&` tag operators.
 */
enum SqlParam:
  case Str(v: String)
  case IntP(v: Int)
  case LongP(v: Long)
  case DoubleP(v: Double)
  case TextArray(v: Seq[String])
  case Timestamp(v: OffsetDateTime)
  case DateP(v: LocalDate)

object SqlParam:

  /**
   * Bind every param positionally (0-based index ↔ `$1..$n`), mirroring the repo's binder style.
   */
  def bindAll(stmt: Statement, params: Seq[SqlParam]): Statement =
    params.zipWithIndex.foldLeft(stmt) { case (s, (p, i)) => bindOne(s, i, p) }

  private def bindOne(s: Statement, i: Int, p: SqlParam): Statement = p match
    case Str(v) => s.bind(i, v)
    case IntP(v) => s.bind(i, Integer.valueOf(v))
    case LongP(v) => s.bind(i, java.lang.Long.valueOf(v))
    case DoubleP(v) => s.bind(i, java.lang.Double.valueOf(v))
    case TextArray(v) => s.bind(i, v.toArray)
    case Timestamp(v) => s.bind(i, v)
    case DateP(v) => s.bind(i, v)
