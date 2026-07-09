package me.cference.artemis.search

/**
 * Stage two of the search compilation pipeline: parse the tokens from [[SearchTokenizer]] into a
 * [[SearchQuery]] AST, as a total function returning `Either[ParseError, SearchQuery]`.
 *
 * A leading `~` marks a flat-OR member, a leading `-` marks a negation, otherwise the term is a
 * plain include (only the first marker is significant — `~` and `-` are not meaningfully combinable
 * in the flat, non-nested OR model). A term containing a `:` is a metatag whose value is parsed
 * into a scalar, comparison, or range; otherwise it is a tag pattern (a `*` is recognized as a
 * wildcard here but expanded only later, against the tag table).
 *
 * Alias resolution, wildcard expansion, SQL compilation, and execution are OUT of scope for this
 * pure front end.
 */
object SearchParser:

  /** Ceiling on positive (include) tag terms per query; beyond it the query is rejected. */
  val MaxPositiveTags: Int = 40

  def parse(raw: String): Either[ParseError, SearchQuery] =
    val tokens = SearchTokenizer.tokenize(raw)
    if tokens.isEmpty then Left(ParseError.EmptyQuery)
    else
      for
        terms <- traverse(tokens)(parseTerm)
        _ <- requirePositiveAnchor(terms)
        _ <- enforceMaxPositiveTags(terms)
      yield SearchQuery(terms)

  // --- term parsing -------------------------------------------------------

  private def parseTerm(token: String): Either[ParseError, Term] =
    if token.startsWith("~") then parseAtom(Polarity.Or, token.drop(1))
    else if token.startsWith("-") then parseAtom(Polarity.Exclude, token.drop(1))
    else parseAtom(Polarity.Include, token)

  private def parseAtom(polarity: Polarity, atom: String): Either[ParseError, Term] =
    atom.indexOf(':') match
      case -1 =>
        if atom.isEmpty then Left(ParseError.EmptyTerm)
        else Right(TagTerm(polarity, TagPattern(atom)))
      case idx =>
        val name = atom.take(idx)
        val rawValue = atom.drop(idx + 1)
        if name.isEmpty then Left(ParseError.MalformedMetatag(s"metatag name is empty in '$atom'"))
        else if rawValue.isEmpty then
          Left(ParseError.MalformedMetatag(s"metatag '$name' has an empty value"))
        else parseValue(rawValue).map(MetaTerm(polarity, name, _))

  private def parseValue(raw: String): Either[ParseError, MetaValue] =
    if raw.contains("..") then parseRange(raw)
    else
      matchCmp(raw) match
        case Some((cmp, rest)) =>
          if rest.isEmpty then Left(ParseError.MalformedMetatag(s"comparison '$raw' has no value"))
          else Right(MetaValue.Compare(cmp, rest))
        case None => Right(MetaValue.Scalar(raw))

  private def matchCmp(raw: String): Option[(Cmp, String)] =
    if raw.startsWith(">=") then Some((Cmp.Ge, raw.drop(2)))
    else if raw.startsWith("<=") then Some((Cmp.Le, raw.drop(2)))
    else if raw.startsWith(">") then Some((Cmp.Gt, raw.drop(1)))
    else if raw.startsWith("<") then Some((Cmp.Lt, raw.drop(1)))
    else None

  private def parseRange(raw: String): Either[ParseError, MetaValue] =
    // Exactly two `..`-separated segments — `a..b..c` (or more) is malformed, not a silent RangeVal.
    raw.split("""\.\.""", -1).toList match
      case List(loStr, hiStr) =>
        val lo = Option.when(loStr.nonEmpty)(loStr)
        val hi = Option.when(hiStr.nonEmpty)(hiStr)
        if lo.isEmpty && hi.isEmpty then
          Left(ParseError.MalformedRange(s"range '$raw' has no bounds"))
        else if lo.exists(startsWithCmp) || hi.exists(startsWithCmp) then
          // A comparator belongs to a `cmp scalar` value, not a range bound (`score:>1..5`).
          Left(ParseError.MalformedRange(s"range '$raw' bound may not start with a comparator"))
        else Right(MetaValue.RangeVal(lo, hi))
      case _ =>
        Left(ParseError.MalformedRange(s"range '$raw' has too many bounds"))

  private def startsWithCmp(s: String): Boolean =
    s.startsWith(">") || s.startsWith("<")

  // --- parse-time guardrails ----------------------------------------------

  /**
   * A query needs at least one non-excluded term (include tag/meta or OR tag) as an index anchor.
   */
  private def requirePositiveAnchor(terms: Seq[Term]): Either[ParseError, Unit] =
    if terms.exists(_.polarity != Polarity.Exclude) then Right(())
    else Left(ParseError.NoPositiveAnchor)

  /** The count of positive (include) tag terms must not exceed [[MaxPositiveTags]]. */
  private def enforceMaxPositiveTags(terms: Seq[Term]): Either[ParseError, Unit] =
    val positiveTags = terms.count {
      case TagTerm(Polarity.Include, _) => true
      case _ => false
    }
    if positiveTags > MaxPositiveTags then
      Left(
        ParseError.TooManyPositiveTags(
          s"query has $positiveTags positive tags; max is $MaxPositiveTags"
        )
      )
    else Right(())

  // --- helpers ------------------------------------------------------------

  /** Sequence per-token results, keeping the first (leftmost) error. */
  private def traverse(tokens: Seq[String])(
      f: String => Either[ParseError, Term]
  ): Either[ParseError, Seq[Term]] =
    tokens.foldLeft(Right(Vector.empty): Either[ParseError, Vector[Term]]) { (acc, token) =>
      for
        soFar <- acc
        term <- f(token)
      yield soFar :+ term
    }
