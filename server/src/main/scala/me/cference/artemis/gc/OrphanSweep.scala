package me.cference.artemis.gc

/**
 * The pure orphan-sweep plan (deletion-lifecycle spec): of the listed `originals/` blobs, the ones
 * that are **debris** — no post references their md5. A blob is protected iff some post (any
 * non-purged status, INCLUDING a still-`pending` in-flight upload) carries its md5; everything else
 * is a failed/abandoned upload's leftover and is planned for deletion.
 *
 * Grace note: Apollo exposes no blob creation timestamp (`ObjectEntry`/`ObjectMetadata` carry
 * none), so the "grace window" that protects in-flight uploads cannot be an age filter here.
 * Instead protection comes from the referenced set including `pending` posts — a committed upload
 * is protected the instant its `CreatePost` projects — with the residual sub-second put→CreatePost
 * (and projection catch-up) gap covered operationally (the sweep is manual/occasional and dry-run
 * first).
 *
 * Format note: matching relies on Apollo's `ObjectEntry.md5` and the posts' md5 being the SAME
 * encoding (lowercase hex, as the ingest path writes). If Apollo ever reported md5 differently
 * (e.g. base64) every blob would read as an orphan — so always dry-run first and eyeball the plan.
 */
object OrphanSweep:

  /** Blobs whose md5 no post references — the deletion plan. */
  def plan(listed: Seq[BlobRef], referencedMd5s: Set[String]): Seq[BlobRef] =
    listed.filterNot(b => referencedMd5s.contains(b.md5))
