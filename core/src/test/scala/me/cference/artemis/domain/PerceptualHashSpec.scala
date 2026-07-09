package me.cference.artemis.domain

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class PerceptualHashSpec extends AnyWordSpec with Matchers:

  "PerceptualHash.hamming" should {

    "return 0 for two identical phashes" in {
      PerceptualHash.hamming(Phash("f00dcafe"), Phash("f00dcafe")) shouldBe 0
    }

    "return 1 for a single differing bit" in {
      // 0x00 XOR 0x01 = 0x01 -> one set bit.
      PerceptualHash.hamming(Phash("00"), Phash("01")) shouldBe 1
    }

    "count every differing bit across the byte string" in {
      // 0x00 XOR 0xff = 0xff (8 bits); second byte 0x0f XOR 0xf0 = 0xff (8 bits) -> 16.
      PerceptualHash.hamming(Phash("000f"), Phash("fff0")) shouldBe 16
    }

    "return Int.MaxValue when the two phashes differ in length" in {
      PerceptualHash.hamming(Phash("f00d"), Phash("f0")) shouldBe Int.MaxValue
    }

    "return Int.MaxValue when a phash is not valid hex" in {
      PerceptualHash.hamming(Phash("zz"), Phash("00")) shouldBe Int.MaxValue
    }

    "return Int.MaxValue when a phash has an odd number of hex digits" in {
      PerceptualHash.hamming(Phash("fff"), Phash("fff")) shouldBe Int.MaxValue
    }
  }
