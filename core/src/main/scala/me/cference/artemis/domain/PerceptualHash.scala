package me.cference.artemis.domain

/**
 * Pure perceptual-hash comparison. A [[Phash]] is a hex string produced by Hephaestus; two are
 * "near" when the Hamming distance (bit-difference count) between their decoded bytes is small.
 *
 * [[hamming]] is total: two phashes that are not bit-for-bit comparable — different byte lengths,
 * an odd digit count, or a non-hex character — yield `Int.MaxValue` ("not comparable / no match")
 * rather than throwing, so a malformed phash can never crash post-processing or spuriously match.
 */
object PerceptualHash:

  private val HexDigits: Set[Char] = "0123456789abcdefABCDEF".toSet

  /** Bit-difference count between two hex phashes, or `Int.MaxValue` if they are not comparable. */
  def hamming(a: Phash, b: Phash): Int =
    (decode(a.value), decode(b.value)) match
      case (Some(xs), Some(ys)) if xs.length == ys.length =>
        xs.zip(ys).map((x, y) => Integer.bitCount(x ^ y)).sum
      case _ => Int.MaxValue

  /**
   * Hex-decode to unsigned byte values, or `None` if empty, odd-length, or containing a non-hex
   * digit.
   */
  private def decode(s: String): Option[Vector[Int]] =
    if s.isEmpty || s.length % 2 != 0 || !s.forall(HexDigits) then None
    else Some(s.grouped(2).map(pair => Integer.parseInt(pair, 16)).toVector)
