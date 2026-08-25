package me.cference.artemis.projection

import io.r2dbc.spi.{
  Connection,
  ConnectionFactories,
  ConnectionFactory,
  ConnectionFactoryOptions,
  Row,
  Statement
}
import me.cference.artemis.config.PostgresConfig
import me.cference.artemis.domain.Derivative
import reactor.core.publisher.{Flux, Mono}

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/**
 * One row of the `posts` read model, carrying the denormalized fields the search DSL and the ITs
 * assert on. `tags` is the GIN-indexed array; `score`/`favCount` the maintained counters. `md5` +
 * `derivatives` back the read API's media refs (search summary): `md5` is `None` for a media-less
 * row, and `derivatives` is empty until processing records them.
 */
final case class PostRow(
    id: String,
    tags: Seq[String],
    status: String,
    score: Int,
    favCount: Int,
    rating: Option[String],
    width: Option[Int],
    height: Option[Int],
    duration: Option[Long],
    parentId: Option[String],
    duplicateOf: Option[String],
    createdAt: Instant,
    md5: Option[String],
    derivatives: Seq[Derivative]
)

/**
 * One tag occurring across a query's matching posts: the tag name, its category, and the count of
 * matches carrying it. Aggregated by [[me.cference.artemis.search.FacetExecutor]] for the gallery's
 * tags-in-results panel (catalog-api "Tag facets for a query").
 */
final case class FacetEntry(name: String, category: Int, count: Int)

/**
 * One tag-name autocomplete suggestion: the trigram-matched name, its category (for the UI color),
 * its `post_count` (the ranking key), and — when the name is an alias antecedent — the canonical
 * consequent it rewrites to.
 */
final case class TagSuggestion(
    name: String,
    category: Int,
    postCount: Int,
    aliasOf: Option[String]
)

/**
 * One related tag for a queried tag X: the co-occurring tag, how many posts carry both (the
 * `cooccurrence` count `co(X,Y)`), and the cosine similarity `co(X,Y) / sqrt(n(X)·n(Y))` used to
 * rank it. Cosine down-weights ubiquitous tags, so a genuinely-correlated tag outranks a common
 * one.
 */
final case class RelatedTag(name: String, cooccurrence: Int, cosine: Double)

/** One pending tag suggestion as projected into the read model for the review queue. */
final case class SuggestionView(tag: String, confidence: Double, source: String)

/**
 * One post awaiting auto-tag review: its id and the pending canonical suggestions (highest
 * confidence first, as merged). Served by [[ReadModelRepository.reviewQueue]] to the Muses review
 * view for batch accept/reject.
 */
final case class ReviewItem(postId: String, suggestions: Vector[SuggestionView])

/**
 * A soft-deleted post due for purge: its id and the EXACT Apollo object keys (within the media
 * bucket) to delete 1:1 — the reconstructed original plus the derivative keys read back from the
 * post's projected `derivatives`. Carrying the real keys (not a guessed prefix) means purge never
 * depends on Hephaestus's derivative-key layout.
 */
final case class PurgeTarget(id: String, blobKeys: Seq[String])

/**
 * Everything the reprocess orchestrator needs to rebuild a post's job: its id, the `md5`/`filetype`
 * that reconstruct the original's Apollo ref (for a Hephaestus `ProcessMediaJob`), and the sample
 * derivative ref (for an Argus `TagJob`), if one exists.
 */
final case class ReprocessInfo(
    id: String,
    md5: String,
    filetype: String,
    sampleRef: Option[String]
)

/**
 * Read model over PostgreSQL: idempotent upserts/updates applied by the projection handlers, and a
 * few read helpers for the ITs. Uses its own short-lived r2dbc connections (not the persistence
 * plugin's pool), so the read side is independent of the write side. Every handler write is
 * idempotent under at-least-once redelivery: upserts keyed by id, absolute `score`/`fav_count`,
 * read-current-delta `post_count` maintenance, and status-guarded delete/restore count adjustments.
 */
object ReadModelRepository:
  /**
   * The `posts` columns projected into a [[PostRow]] — shared by point reads and DSL search.
   * `derivatives` is read as `::text` (JSONB rendered to a JSON string) so [[parseDerivatives]] can
   * fold it into the row's media refs.
   */
  val PostColumns: String =
    "id, tags, status, score, fav_count, rating, width, height, duration, parent_id, " +
      "duplicate_of, created_at, md5, derivatives::text AS derivatives"

  /**
   * Parse the derivative object keys (within the media bucket) from the projected `derivatives`
   * JSON (`[{"kind":...,"ref":"<bucket>/<object>"}]`). The stored `ref` is `bucket/object`, so the
   * object is everything after the first `/`. Total: a malformed/absent element is skipped, so
   * purge never throws over a legacy row.
   */
  def derivativeObjectKeys(derivativesJson: String): Seq[String] =
    import spray.json.*
    derivativesJson.parseJson match
      case JsArray(elems) =>
        elems.flatMap {
          case JsObject(fields) =>
            fields.get("ref").collect {
              case JsString(ref) if ref.contains('/') =>
                ref.substring(ref.indexOf('/') + 1)
            }
          case _ => None
        }
      case _ => Seq.empty

  /**
   * Parse the full `[{kind, ref}]` derivatives out of the projected `derivatives` JSON into domain
   * [[Derivative]] values (`ref` = `<bucket>/<object>`). Backs the read API's media refs on the
   * search summary; the http layer derives each `variant` from the ref. Total: a malformed/absent
   * element (missing `kind`/`ref`) is skipped rather than throwing over a legacy row.
   */
  def parseDerivatives(derivativesJson: String): Seq[Derivative] =
    import spray.json.*
    derivativesJson.parseJson match
      case JsArray(elems) =>
        elems.flatMap {
          case JsObject(fields) =>
            (fields.get("kind"), fields.get("ref")) match
              case (Some(JsString(kind)), Some(JsString(ref))) => Some(Derivative(kind, ref))
              case _ => None
          case _ => None
        }
      case _ => Seq.empty

  /**
   * The full `bucket/object` ref of the `sample` derivative from the projected `derivatives` JSON,
   * or `None` if there is no sample. Used to rebuild an Argus `TagJob` on a `tags` reprocess.
   */
  def sampleRef(derivativesJson: String): Option[String] =
    import spray.json.*
    derivativesJson.parseJson match
      case JsArray(elems) =>
        elems.collectFirst {
          case JsObject(fields)
              if fields.get("kind").contains(JsString("sample")) &&
                fields.get("ref").exists(_.isInstanceOf[JsString]) =>
            fields("ref").asInstanceOf[JsString].value
        }
      case _ => None

final class ReadModelRepository(cfg: PostgresConfig)(using ec: ExecutionContext):

  private val factory: ConnectionFactory =
    ConnectionFactories.get(
      ConnectionFactoryOptions
        .builder()
        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
        .option(ConnectionFactoryOptions.HOST, cfg.host)
        .option(ConnectionFactoryOptions.PORT, Integer.valueOf(cfg.port))
        .option(ConnectionFactoryOptions.DATABASE, cfg.database)
        .option(ConnectionFactoryOptions.USER, cfg.user)
        .option(ConnectionFactoryOptions.PASSWORD, cfg.password)
        .build()
    )

  // --- posts (idempotent, keyed by id) ---------------------------------------

  /** Create/refresh the media+status fields; leave tags/counters intact on conflict. */
  def upsertPostCreated(
      id: String,
      md5: String,
      filetype: String,
      status: String,
      createdAt: Instant
  ): Future[Unit] =
    update(
      """INSERT INTO posts (id, md5, filetype, status, created_at)
        |VALUES ($1, $2, $3, $4, $5)
        |ON CONFLICT (id) DO UPDATE SET
        |  md5 = EXCLUDED.md5, filetype = EXCLUDED.filetype, status = EXCLUDED.status""".stripMargin,
      _.bind(0, id)
        .bind(1, md5)
        .bind(2, filetype)
        .bind(3, status)
        .bind(4, createdAt.atOffset(ZoneOffset.UTC))
    ).map(_ => ())

  def applyMediaProcessed(
      id: String,
      width: Int,
      height: Int,
      duration: Option[Long],
      phash: String,
      derivativesJson: String,
      status: String,
      specVersion: Int
  ): Future[Unit] =
    update(
      // A metadata-only reprocess returns no derivatives; keep the existing ones rather than
      // wiping to `[]` (mirrors the aggregate's evolve — empty means "this job made none").
      """UPDATE posts SET
        |  width = $2, height = $3, duration = $4, phash = $5,
        |  derivatives = CASE WHEN CAST($6 AS JSONB) = '[]'::jsonb THEN derivatives
        |                     ELSE CAST($6 AS JSONB) END,
        |  status = $7, derivative_spec_version = $8
        |WHERE id = $1""".stripMargin,
      s =>
        val bound = s
          .bind(0, id)
          .bind(1, Integer.valueOf(width))
          .bind(2, Integer.valueOf(height))
        val withDuration = duration match
          case Some(ms) => bound.bind(3, java.lang.Long.valueOf(ms))
          case None => bound.bindNull(3, classOf[java.lang.Long])
        withDuration
          .bind(4, phash)
          .bind(5, derivativesJson)
          .bind(6, status)
          .bind(7, Integer.valueOf(specVersion))
    ).map(_ => ())

  def setStatus(id: String, status: String): Future[Unit] =
    update("UPDATE posts SET status = $2 WHERE id = $1", _.bind(0, id).bind(1, status))
      .map(_ => ())

  /**
   * Mark a post deleted and drop its tags from `tags.post_count` — a tombstone must not inflate
   * search counts. Reads the current status first: only a non-deleted post decrements, so
   * redelivery of the same `PostDeleted` is a no-op (same guard trick as `fav_count`). `ChangeTags`
   * is rejected on a deleted post by the domain, so counts can't drift while deleted.
   */
  def deletePost(id: String, deletedAt: Instant): Future[Unit] =
    postStatusAndTags(id).flatMap {
      case Some((status, tags)) if status != "deleted" =>
        for
          _ <- sequentially(tags)(decrementTagCount)
          _ <- sequentially(pairsOf(tags.toSet).toSeq)(
            decrementPair
          ) // drop the post's co-occurrence
          _ <- update(
            "UPDATE posts SET status = 'deleted', deleted_at = $2 WHERE id = $1",
            _.bind(0, id).bind(1, deletedAt.atOffset(ZoneOffset.UTC))
          )
        yield ()
      case _ => Future.successful(()) // already deleted or absent: no-op
    }

  /** Reverse of [[deletePost]]: re-add the tags to the counts + co-occurrence, then set active. */
  def restorePost(id: String): Future[Unit] =
    postStatusAndTags(id).flatMap {
      case Some((status, tags)) if status == "deleted" =>
        for
          _ <- sequentially(tags)(incrementTagCount)
          _ <- sequentially(pairsOf(tags.toSet).toSeq)(incrementPair)
          _ <- update(
            "UPDATE posts SET status = 'active', deleted_at = NULL WHERE id = $1",
            _.bind(0, id)
          )
        yield ()
      case _ => Future.successful(()) // already active or absent: no-op
    }

  // --- deduplicated ingest + purge (deletion-lifecycle) ----------------------

  /**
   * The id + status of the (at most one) non-purged post carrying `md5`, for the upload dedup
   * branch (merge on a live match, restore on a soft-deleted match, create when absent). Purged
   * posts have no row, so a purged md5 correctly reads as absent → a fresh upload.
   */
  def findByMd5(md5: String): Future[Option[(String, String)]] =
    query(
      "SELECT id, status FROM posts WHERE md5 = $1 ORDER BY created_at DESC LIMIT 1",
      _.bind(0, md5)
    )(row => (row.get("id", classOf[String]), row.get("status", classOf[String]))).map(_.headOption)

  /**
   * Soft-deleted posts whose `deleted_at` is older than `cutoff` — the retention/auto-purge job's
   * work list, each with the EXACT blob keys to delete 1:1: the reconstructed original
   * (`originals/<md5[:2]>/<md5>.<ext>`, mirroring `UploadService.originalKey`) plus the derivative
   * object keys parsed from the projected `derivatives` (stripping the bucket prefix). Reading the
   * real derivative keys avoids guessing Hephaestus's key layout.
   */
  def softDeletedBefore(cutoff: Instant): Future[Seq[PurgeTarget]] =
    query(
      """SELECT id, md5, filetype, derivatives::text AS derivatives FROM posts
        |WHERE status = 'deleted' AND deleted_at IS NOT NULL AND deleted_at < $1
        |ORDER BY deleted_at ASC""".stripMargin,
      _.bind(0, cutoff.atOffset(ZoneOffset.UTC))
    ) { row =>
      val md5 = row.get("md5", classOf[String])
      val ext = Option(row.get("filetype", classOf[String]))
        .flatMap(_.split('/').lastOption)
        .filter(_.nonEmpty)
        .getOrElse("bin")
      val originalKey = s"originals/${md5.take(2)}/$md5.$ext"
      val derivativeKeys =
        ReadModelRepository.derivativeObjectKeys(row.get("derivatives", classOf[String]))
      PurgeTarget(row.get("id", classOf[String]), originalKey +: derivativeKeys)
    }

  /**
   * The single-post analogue of [[softDeletedBefore]]: the EXACT blob keys to delete 1:1 for the
   * soft-deleted post `id` (the reconstructed original + the projected derivative keys), or `None`
   * when the post is not currently `deleted` (never existed, still active, or already purged).
   * Backs the immediate hard-purge admin endpoint; the same row extraction as the retention
   * work-list.
   */
  def purgeTargetFor(id: String): Future[Option[PurgeTarget]] =
    query(
      """SELECT id, md5, filetype, derivatives::text AS derivatives FROM posts
        |WHERE id = $1 AND status = 'deleted'""".stripMargin,
      _.bind(0, id)
    ) { row =>
      val md5 = row.get("md5", classOf[String])
      val ext = Option(row.get("filetype", classOf[String]))
        .flatMap(_.split('/').lastOption)
        .filter(_.nonEmpty)
        .getOrElse("bin")
      val originalKey = s"originals/${md5.take(2)}/$md5.$ext"
      val derivativeKeys =
        ReadModelRepository.derivativeObjectKeys(row.get("derivatives", classOf[String]))
      PurgeTarget(row.get("id", classOf[String]), originalKey +: derivativeKeys)
    }.map(_.headOption)

  /**
   * Every md5 still referenced by a post (any non-purged status, including `pending` in-flight
   * uploads) — the orphan sweep's protected set: a blob whose md5 is here is NOT debris.
   */
  def referencedMd5s(): Future[Set[String]] =
    query("SELECT DISTINCT md5 FROM posts WHERE md5 IS NOT NULL", identity)(
      _.get("md5", classOf[String])
    ).map(_.toSet)

  /**
   * Remove a purged post's read-model row (from `PostPurged`). The tag/co-occurrence counts were
   * already decremented at soft-delete, so this only drops the now-orphan row.
   */
  def purgePost(id: String): Future[Unit] =
    update("DELETE FROM posts WHERE id = $1", _.bind(0, id)).map(_ => ())

  // --- reprocessing (processing-versions + reprocess-orchestration) ----------

  /**
   * Ids of active posts whose stamp in `versionColumn` is below `currentVersion` — the `stale`
   * reprocess selection. `versionColumn` is a fixed column name from `ReprocessKind` (never user
   * input), so interpolating it is safe. Ordered by id and capped, so a big backfill is chunked and
   * a re-run naturally resumes (already-current posts drop out).
   */
  def stalePostIds(versionColumn: String, currentVersion: Int, limit: Int): Future[Seq[String]] =
    query(
      s"""SELECT id FROM posts
         |WHERE status = 'active' AND $versionColumn < $$1
         |ORDER BY id LIMIT $$2""".stripMargin,
      _.bind(0, Integer.valueOf(currentVersion)).bind(1, Integer.valueOf(limit))
    )(_.get("id", classOf[String]))

  /** Per-post info to rebuild a reprocess job, for the given (active) post ids. */
  def reprocessInfo(ids: Seq[String]): Future[Seq[ReprocessInfo]] =
    if ids.isEmpty then Future.successful(Seq.empty)
    else
      query(
        """SELECT id, md5, filetype, derivatives::text AS derivatives FROM posts
          |WHERE status = 'active' AND id = ANY($1)""".stripMargin,
        _.bind(0, ids.toArray)
      ) { row =>
        ReprocessInfo(
          row.get("id", classOf[String]),
          row.get("md5", classOf[String]),
          row.get("filetype", classOf[String]),
          ReadModelRepository.sampleRef(row.get("derivatives", classOf[String]))
        )
      }

  private def postStatusAndTags(id: String): Future[Option[(String, Seq[String])]] =
    query("SELECT status, tags FROM posts WHERE id = $1", _.bind(0, id)) { row =>
      val status = row.get("status", classOf[String])
      val tags = Option(row.get("tags", classOf[Array[String]])).map(_.toSeq).getOrElse(Seq.empty)
      (status, tags)
    }.map(_.headOption)

  def setRating(id: String, code: String): Future[Unit] =
    update("UPDATE posts SET rating = $2 WHERE id = $1", _.bind(0, id).bind(1, code))
      .map(_ => ())

  def setParent(id: String, parentId: String): Future[Unit] =
    update("UPDATE posts SET parent_id = $2 WHERE id = $1", _.bind(0, id).bind(1, parentId))
      .map(_ => ())

  def setSource(id: String, source: String): Future[Unit] =
    update("UPDATE posts SET source = $2 WHERE id = $1", _.bind(0, id).bind(1, source))
      .map(_ => ())

  /** Record that a post is a possible perceptual duplicate of `matchedId` — an idempotent set. */
  def setDuplicateOf(id: String, matchedId: String): Future[Unit] =
    update("UPDATE posts SET duplicate_of = $2 WHERE id = $1", _.bind(0, id).bind(1, matchedId))
      .map(_ => ())

  // --- auto-tagging review (idempotent) --------------------------------------

  /**
   * Record a post's pending suggestions and flag it for review (from `SuggestionsRecorded`).
   * `suggestionsJson` is the canonical `[{tag,confidence,source}]` array. Idempotent: re-applying
   * the same event sets the same rows.
   */
  def setSuggestions(id: String, suggestionsJson: String, taggerVersion: Int): Future[Unit] =
    update(
      """UPDATE posts SET needs_review = TRUE, suggestions = $2::jsonb, tagger_version = $3
        |WHERE id = $1""".stripMargin,
      _.bind(0, id).bind(1, suggestionsJson).bind(2, Integer.valueOf(taggerVersion))
    ).map(_ => ())

  /** Clear the review flag and empty the pending suggestions (from `SuggestionsReviewed`). */
  def clearReview(id: String): Future[Unit] =
    update(
      "UPDATE posts SET needs_review = FALSE, suggestions = '[]'::jsonb WHERE id = $1",
      _.bind(0, id)
    ).map(_ => ())

  /**
   * The auto-tag review queue: posts flagged `needs_review`, oldest first (batch review works
   * front-to-back through the backlog), each with its pending canonical suggestions. `suggestions`
   * is read as text and parsed — the JSON is the projection's own controlled output.
   */
  def reviewQueue(limit: Int): Future[Seq[ReviewItem]] =
    query(
      """SELECT id, suggestions::text AS suggestions FROM posts
        |WHERE needs_review
        |ORDER BY created_at ASC, id ASC
        |LIMIT $1""".stripMargin,
      _.bind(0, Integer.valueOf(limit))
    ) { row =>
      ReviewItem(
        row.get("id", classOf[String]),
        SuggestionJson.parse(row.get("suggestions", classOf[String]))
      )
    }

  /**
   * The `(id, phash)` pairs of active posts with a non-empty phash, excluding `excludeId`. Feeds
   * near-duplicate detection — the just-activated post is compared against these existing phashes.
   */
  def activePhashes(excludeId: String): Future[Seq[(String, String)]] =
    query(
      """SELECT id, phash FROM posts
        |WHERE status = 'active' AND id <> $1 AND phash IS NOT NULL AND phash <> ''
        |ORDER BY id""".stripMargin,
      _.bind(0, excludeId)
    ) { row =>
      (row.get("id", classOf[String]), row.get("phash", classOf[String]))
    }

  /** A post's perceptual hash, if it has one (a pending/failed/absent post has none). */
  def phashOf(id: String): Future[Option[String]] =
    query(
      "SELECT phash FROM posts WHERE id = $1 AND phash IS NOT NULL AND phash <> ''",
      _.bind(0, id)
    )(row => row.get("phash", classOf[String])).map(_.headOption)

  /** Set the score absolutely (the `Scored` event carries the total) — a pure idempotent upsert. */
  def setScore(id: String, score: Int): Future[Unit] =
    update(
      "UPDATE posts SET score = $2 WHERE id = $1",
      _.bind(0, id).bind(1, Integer.valueOf(score))
    ).map(_ => ())

  /** Single-user favorite is a boolean, so set `fav_count` absolutely (1/0) — idempotent. */
  def setFavorited(id: String, favorited: Boolean): Future[Unit] =
    update(
      "UPDATE posts SET fav_count = $2 WHERE id = $1",
      _.bind(0, id).bind(1, Integer.valueOf(if favorited then 1 else 0))
    ).map(_ => ())

  /**
   * Replace the post's tag set and reconcile `tags.post_count`. Reads the CURRENT tags first so the
   * added/removed deltas are computed against actual membership: re-applying the same `TagsChanged`
   * is a zero-delta no-op (idempotent on redelivery), and rebuild-from-empty adds everything.
   *
   * Caveat: the read + the `tags[]` write + the count deltas span separate short-lived connections,
   * not one transaction. Single-node replay (the projection processes one post's events serially)
   * stays consistent; a single transactional `R2dbcSession` write is the clustered-runtime
   * follow-up if concurrent handlers ever touch the same post.
   */
  def setTags(id: String, newTags: Seq[String]): Future[Unit] =
    currentTags(id).flatMap { current =>
      val newSet = newTags.toSet
      val currentSet = current.toSet
      val added = (newSet -- currentSet).toSeq
      val removed = (currentSet -- newSet).toSeq
      // Co-occurrence (related-tags): the post's contribution is every pair among its tags, so the
      // delta is the pair-set difference — pairs newly present increment, pairs gone decrement,
      // pairs in both untouched. Reusing the same current-vs-new read keeps it idempotent (re-applying
      // the same TagsChanged is a zero-delta no-op) and correct on rebuild-from-empty.
      val oldPairs = pairsOf(currentSet)
      val newPairs = pairsOf(newSet)
      for
        _ <- update(
          "UPDATE posts SET tags = $2 WHERE id = $1",
          _.bind(0, id).bind(1, newTags.toArray)
        )
        _ <- sequentially(added)(incrementTagCount)
        _ <- sequentially(removed)(decrementTagCount)
        _ <- sequentially((newPairs -- oldPairs).toSeq)(incrementPair)
        _ <- sequentially((oldPairs -- newPairs).toSeq)(decrementPair)
      yield ()
    }

  private def currentTags(id: String): Future[Seq[String]] =
    query("SELECT tags FROM posts WHERE id = $1", _.bind(0, id)) { row =>
      Option(row.get("tags", classOf[Array[String]])).map(_.toSeq).getOrElse(Seq.empty)
    }.map(_.headOption.getOrElse(Seq.empty))

  private def incrementTagCount(name: String): Future[Unit] =
    update(
      """INSERT INTO tags (name, post_count) VALUES ($1, 1)
        |ON CONFLICT (name) DO UPDATE SET post_count = tags.post_count + 1""".stripMargin,
      _.bind(0, name)
    ).map(_ => ())

  private def decrementTagCount(name: String): Future[Unit] =
    update(
      "UPDATE tags SET post_count = GREATEST(post_count - 1, 0) WHERE name = $1",
      _.bind(0, name)
    ).map(_ => ())

  // --- tag co-occurrence (related-tags) --------------------------------------

  /**
   * The canonical unordered pairs among a set of tags: each pair once as `(a, b)` with `a < b`, so
   * a pair is stored a single way in `tag_cooccurrence` and never double-counted.
   */
  private def pairsOf(tags: Set[String]): Set[(String, String)] =
    val sorted = tags.toVector.sorted
    (for
      i <- sorted.indices
      j <- (i + 1) until sorted.length
    yield (sorted(i), sorted(j))).toSet

  private def incrementPair(pair: (String, String)): Future[Unit] =
    update(
      """INSERT INTO tag_cooccurrence (tag_a, tag_b, count) VALUES ($1, $2, 1)
        |ON CONFLICT (tag_a, tag_b) DO UPDATE SET count = tag_cooccurrence.count + 1""".stripMargin,
      _.bind(0, pair._1).bind(1, pair._2)
    ).map(_ => ())

  private def decrementPair(pair: (String, String)): Future[Unit] =
    update(
      "UPDATE tag_cooccurrence SET count = GREATEST(count - 1, 0) WHERE tag_a = $1 AND tag_b = $2",
      _.bind(0, pair._1).bind(1, pair._2)
    ).map(_ => ())

  /**
   * The top related tags for `tag` X, ranked by cosine `co(X,Y) / sqrt(n(X)·n(Y))` computed from
   * the co-occurrence table + `tags.post_count`. Served from the projection (a lookup on either
   * side of the pair, not a library scan); excludes X itself (pairs are between distinct tags) and
   * any tag with no live posts. Empty when X has no co-occurrences or no posts.
   */
  def relatedTags(tag: String, limit: Int): Future[Seq[RelatedTag]] =
    query(
      """WITH nx AS (SELECT post_count AS n FROM tags WHERE name = $1)
        |SELECT co.other AS name, co.cooc AS cooc,
        |       co.cooc::float8 / sqrt(nx.n::float8 * t.post_count::float8) AS cosine
        |FROM (
        |  SELECT (CASE WHEN tag_a = $1 THEN tag_b ELSE tag_a END) AS other, count AS cooc
        |  FROM tag_cooccurrence
        |  WHERE (tag_a = $1 OR tag_b = $1) AND count > 0
        |) co
        |CROSS JOIN nx
        |JOIN tags t ON t.name = co.other
        |WHERE nx.n > 0 AND t.post_count > 0
        |ORDER BY cosine DESC, name ASC
        |LIMIT $2""".stripMargin,
      _.bind(0, tag).bind(1, Integer.valueOf(limit))
    ) { row =>
      RelatedTag(
        row.get("name", classOf[String]),
        row.get("cooc", classOf[Integer]).intValue,
        row.get("cosine", classOf[java.lang.Double]).doubleValue
      )
    }

  // --- pools (idempotent) ----------------------------------------------------

  def upsertPool(id: String, name: String): Future[Unit] =
    update(
      """INSERT INTO pools (id, name) VALUES ($1, $2)
        |ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name""".stripMargin,
      _.bind(0, id).bind(1, name)
    ).map(_ => ())

  def renamePool(id: String, name: String): Future[Unit] =
    update("UPDATE pools SET name = $2 WHERE id = $1", _.bind(0, id).bind(1, name)).map(_ => ())

  def deletePool(id: String): Future[Unit] =
    update("DELETE FROM pool_posts WHERE pool_id = $1", _.bind(0, id))
      .flatMap(_ => update("DELETE FROM pools WHERE id = $1", _.bind(0, id)))
      .map(_ => ())

  /** Append a post at the end of the pool. Position derives from current membership size. */
  def addPoolPost(poolId: String, postId: String): Future[Unit] =
    poolSize(poolId).flatMap { size =>
      update(
        """INSERT INTO pool_posts (pool_id, post_id, position) VALUES ($1, $2, $3)
          |ON CONFLICT (pool_id, post_id) DO NOTHING""".stripMargin,
        _.bind(0, poolId).bind(1, postId).bind(2, Integer.valueOf(size))
      ).map(_ => ())
    }

  def removePoolPost(poolId: String, postId: String): Future[Unit] =
    update(
      "DELETE FROM pool_posts WHERE pool_id = $1 AND post_id = $2",
      _.bind(0, poolId).bind(1, postId)
    ).map(_ => ())

  /** Rewrite positions so they match the given order (0-based). */
  def reorderPool(poolId: String, orderedPostIds: Seq[String]): Future[Unit] =
    sequentially(orderedPostIds.zipWithIndex) { case (postId, position) =>
      update(
        "UPDATE pool_posts SET position = $3 WHERE pool_id = $1 AND post_id = $2",
        _.bind(0, poolId).bind(1, postId).bind(2, Integer.valueOf(position))
      ).map(_ => ())
    }

  private def poolSize(poolId: String): Future[Int] =
    query("SELECT COUNT(*) AS n FROM pool_posts WHERE pool_id = $1", _.bind(0, poolId)) { row =>
      row.get("n", classOf[java.lang.Long]).intValue
    }.map(_.headOption.getOrElse(0))

  // --- reads (IT assertions) -------------------------------------------------

  def getPost(id: String): Future[Option[PostRow]] =
    query(
      s"SELECT ${ReadModelRepository.PostColumns} FROM posts WHERE id = $$1",
      _.bind(0, id)
    )(mapPostRow).map(_.headOption)

  /**
   * Run a pre-compiled search SQL (from `SqlCompiler`) with its bound params applied by `bind`, and
   * map each row to a [[PostRow]]. The r2dbc plumbing (short-lived connection, row mapping) is
   * reused; the caller owns the SQL and the parameter binding so this stays search-agnostic.
   */
  def selectPostRows(sql: String, bind: Statement => Statement): Future[Seq[PostRow]] =
    query(sql, bind)(mapPostRow)

  /**
   * Run a pre-compiled facet-aggregation SQL (from `FacetExecutor`) with its bound params and map
   * each row to a [[FacetEntry]]. Kept search-agnostic like [[selectPostRows]]: the caller owns the
   * SQL (built from `SqlCompiler.compileWhere`) and the binding; this only reuses the r2dbc
   * plumbing. Columns expected: `name` (text), `category` (int), `cnt` (bigint from `COUNT(*)`).
   */
  def selectFacets(sql: String, bind: Statement => Statement): Future[Seq[FacetEntry]] =
    query(sql, bind) { row =>
      FacetEntry(
        name = row.get("name", classOf[String]),
        category = row.get("category", classOf[Integer]).intValue,
        count = row.get("cnt", classOf[java.lang.Long]).intValue
      )
    }

  private def mapPostRow(row: Row): PostRow =
    PostRow(
      id = row.get("id", classOf[String]),
      tags = Option(row.get("tags", classOf[Array[String]])).map(_.toSeq).getOrElse(Seq.empty),
      status = row.get("status", classOf[String]),
      score = row.get("score", classOf[Integer]).intValue,
      favCount = row.get("fav_count", classOf[Integer]).intValue,
      rating = Option(row.get("rating", classOf[String])),
      width = Option(row.get("width", classOf[Integer])).map(_.intValue),
      height = Option(row.get("height", classOf[Integer])).map(_.intValue),
      duration = Option(row.get("duration", classOf[java.lang.Long])).map(_.longValue),
      parentId = Option(row.get("parent_id", classOf[String])),
      duplicateOf = Option(row.get("duplicate_of", classOf[String])),
      createdAt = row.get("created_at", classOf[OffsetDateTime]).toInstant,
      md5 = Option(row.get("md5", classOf[String])),
      derivatives = Option(row.get("derivatives", classOf[String]))
        .map(ReadModelRepository.parseDerivatives)
        .getOrElse(Seq.empty)
    )

  /**
   * Names in the trigram-indexed `tags` table matching a SQL `LIKE` pattern, most-populated first.
   * Backs query-time wildcard expansion (search-dsl 3.2): the caller passes an already-escaped
   * pattern (literal `_`/`%` escaped, `*`→`%`) and a `limit` of `cap + 1`, so an over-broad pattern
   * surfaces as one row beyond the cap rather than a full-table scan. `name` tiebreaks the ordering
   * for determinism.
   */
  def matchTagNames(likePattern: String, limit: Int): Future[Seq[String]] =
    query(
      """SELECT name FROM tags WHERE name LIKE $1 ESCAPE '\'
        |ORDER BY post_count DESC, name ASC
        |LIMIT $2""".stripMargin,
      _.bind(0, likePattern).bind(1, Integer.valueOf(limit))
    )(row => row.get("name", classOf[String]))

  /**
   * Context-aware tag autocomplete (catalog-api task 5.3): prefix-match `tags.name` against `q`
   * (index-friendly `q%` on the trigram-indexed column), rank most-populated first, and LEFT JOIN
   * `tag_aliases` so an alias antecedent carries its canonical consequent. `q` is escaped for LIKE
   * (its `_`/`%`/`\` are literalized) and bound as a param — never interpolated (injection-safe).
   */
  def autocompleteTags(q: String, limit: Int): Future[Seq[TagSuggestion]] =
    query(
      """SELECT t.name, t.category, t.post_count, a.consequent AS alias_of
        |FROM tags t
        |LEFT JOIN tag_aliases a ON a.antecedent = t.name
        |WHERE t.name LIKE $1 ESCAPE '\'
        |ORDER BY t.post_count DESC, t.name ASC
        |LIMIT $2""".stripMargin,
      _.bind(0, escapeLikePrefix(q)).bind(1, Integer.valueOf(limit))
    ) { row =>
      TagSuggestion(
        name = row.get("name", classOf[String]),
        category = row.get("category", classOf[Integer]).intValue,
        postCount = row.get("post_count", classOf[Integer]).intValue,
        aliasOf = Option(row.get("alias_of", classOf[String]))
      )
    }

  /** Escape LIKE metacharacters in `q` and append `%` for a literal prefix match (`ESCAPE '\'`). */
  private def escapeLikePrefix(q: String): String =
    val escaped = q.iterator.flatMap {
      case c @ ('_' | '%' | '\\') => s"\\$c"
      case c => c.toString
    }.mkString
    s"$escaped%"

  def tagPostCount(name: String): Future[Int] =
    query("SELECT post_count FROM tags WHERE name = $1", _.bind(0, name)) { row =>
      row.get("post_count", classOf[Integer]).intValue
    }.map(_.headOption.getOrElse(0))

  def poolPosts(poolId: String): Future[Seq[(String, Int)]] =
    query(
      "SELECT post_id, position FROM pool_posts WHERE pool_id = $1 ORDER BY position",
      _.bind(0, poolId)
    ) { row =>
      (row.get("post_id", classOf[String]), row.get("position", classOf[Integer]).intValue)
    }

  def getPool(id: String): Future[Option[(String, String)]] =
    query("SELECT id, name FROM pools WHERE id = $1", _.bind(0, id)) { row =>
      (row.get("id", classOf[String]), row.get("name", classOf[String]))
    }.map(_.headOption)

  // --- r2dbc plumbing --------------------------------------------------------

  /** Run effects one-at-a-time so ordered SQL (per-tag counts, reorder) stays deterministic. */
  private def sequentially[A](items: Seq[A])(f: A => Future[Unit]): Future[Unit] =
    items.foldLeft(Future.successful(()))((acc, a) => acc.flatMap(_ => f(a)))

  private def update(sql: String, bind: Statement => Statement): Future[Long] =
    withConnection { conn =>
      Mono
        .from(bind(conn.createStatement(sql)).execute())
        .flatMap(result => Mono.from(result.getRowsUpdated))
        .map(_.longValue)
    }

  private def query[A](sql: String, bind: Statement => Statement)(map: Row => A): Future[Seq[A]] =
    withConnection { conn =>
      Flux
        .from(bind(conn.createStatement(sql)).execute())
        .flatMap(result => result.map((row, _) => map(row)))
        .collectList()
        .map(_.asScala.toVector)
    }

  private def withConnection[A](use: Connection => Mono[A]): Future[A] =
    toFuture(
      Mono.usingWhen[A, Connection](
        Mono.from[Connection](factory.create()),
        (conn: Connection) => use(conn),
        (conn: Connection) => conn.close()
      )
    )

  private def toFuture[A](mono: Mono[A]): Future[A] =
    val promise = Promise[A]()
    mono.subscribe(
      (value: A) => { val _ = promise.trySuccess(value) },
      (err: Throwable) => { val _ = promise.tryFailure(err) },
      () => {
        val _ = promise.trySuccess(null.asInstanceOf[A])
      } // scalafix:ok DisableSyntax.null
    )
    promise.future
