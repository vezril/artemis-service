package me.cference.artemis.search

/**
 * Stage one of the search compilation pipeline: split a raw query string into term tokens on
 * unquoted whitespace, keeping a `"quoted phrase"` as a single token so a tag or metatag value may
 * carry spaces. Quote characters are grouping syntax only and are stripped from the emitted token;
 * the term punctuation the parser needs (`~`, `-`, `:`, `*`, `..`, comparison operators) is carried
 * through untouched. A blank or empty query yields no tokens.
 */
object SearchTokenizer:

  /** Fold state: completed tokens, the in-progress token, and whether we are inside quotes. */
  final private case class Scan(tokens: Vector[String], current: String, inQuotes: Boolean)

  def tokenize(raw: String): Seq[String] =
    val scanned = raw.foldLeft(Scan(Vector.empty, "", inQuotes = false)) { (state, ch) =>
      if ch == '"' then state.copy(inQuotes = !state.inQuotes)
      else if ch.isWhitespace && !state.inQuotes then
        if state.current.isEmpty then state
        else Scan(state.tokens :+ state.current, "", state.inQuotes)
      else state.copy(current = state.current + ch)
    }
    if scanned.current.isEmpty then scanned.tokens else scanned.tokens :+ scanned.current
