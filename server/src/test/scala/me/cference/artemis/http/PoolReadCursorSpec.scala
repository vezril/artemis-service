package me.cference.artemis.http

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Unit tests for the pool read cursors. The point of the field-safe `.`-joined base64 encoding is
 * that a pool name or id containing spaces/colons/dots round-trips intact (a naive space/colon
 * delimiter would corrupt page 2), and that any garbage decodes to a `Left` (→ 400 at the route),
 * never an exception.
 */
final class PoolReadCursorSpec extends AnyWordSpec with Matchers:

  "PoolListCursor" should {
    "round-trip a name with spaces, colons and dots" in {
      val c = PoolListCursor("my summer pool: v1.2", "pool|42")
      PoolListCursor.decode(c.encode) shouldBe Right(c)
    }

    "reject a malformed cursor as a Left (no throw)" in {
      PoolListCursor.decode("not-a-valid-cursor").isLeft shouldBe true
      PoolListCursor.decode("only-one-segment").isLeft shouldBe true
    }
  }

  "PoolPostsCursor" should {
    "round-trip a position and a post id" in {
      val c = PoolPostsCursor(7, "632220a6-94d6-476f-82c3-37456b53510c")
      PoolPostsCursor.decode(c.encode) shouldBe Right(c)
    }

    "reject a non-integer position" in {
      // A well-formed two-segment cursor whose first field isn't an int.
      val bogus = PoolListCursor("notanint", "p1").encode
      PoolPostsCursor.decode(bogus).isLeft shouldBe true
    }

    "reject a malformed cursor as a Left (no throw)" in {
      PoolPostsCursor.decode("garbage").isLeft shouldBe true
    }
  }
