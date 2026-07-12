package me.cference.artemis.gc

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Pure orphan-sweep plan (deletion-lifecycle spec, task 4.1): a blob whose md5 no post references
 * is debris; a blob referenced by any post — including a still-`pending` in-flight upload — is
 * protected.
 */
final class OrphanSweepSpec extends AnyWordSpec with Matchers:

  "OrphanSweep.plan" should {

    "select blobs whose md5 no post references" in {
      val listed = Seq(
        BlobRef("originals/aa/aaa.png", "aaa"),
        BlobRef("originals/bb/bbb.png", "bbb"),
        BlobRef("originals/cc/ccc.png", "ccc")
      )
      // aaa + ccc are referenced; bbb is debris.
      OrphanSweep.plan(listed, Set("aaa", "ccc")) shouldBe Seq(
        BlobRef("originals/bb/bbb.png", "bbb")
      )
    }

    "protect a freshly-written blob whose (pending) post already references its md5" in {
      // The referenced set includes pending posts, so an in-flight upload's blob is not swept.
      val listed = Seq(BlobRef("originals/dd/ddd.png", "ddd"))
      OrphanSweep.plan(listed, Set("ddd")) shouldBe empty
    }

    "return nothing when every blob is referenced" in {
      val listed = Seq(BlobRef("originals/aa/aaa.png", "aaa"))
      OrphanSweep.plan(listed, Set("aaa")) shouldBe empty
    }

    "return nothing for an empty listing" in {
      OrphanSweep.plan(Seq.empty, Set("aaa")) shouldBe empty
    }
  }
