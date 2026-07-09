package me.cference.artemis.projection

import me.cference.artemis.config.PostgresConfig
import io.r2dbc.spi.{
  Connection,
  ConnectionFactories,
  ConnectionFactory,
  ConnectionFactoryOptions,
  Row,
  Statement
}
import reactor.core.publisher.{Flux, Mono}

import java.time.{Instant, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/**
 * One row of the `posts` read model, carrying the denormalized fields the search DSL and the ITs
 * assert on. `tags` is the GIN-indexed array; `score`/`favCount` the maintained counters.
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
    parentId: Option[String]
)

/**
 * Read model over PostgreSQL: idempotent upserts/updates applied by the projection handlers, and a
 * few read helpers for the ITs. Uses its own short-lived r2dbc connections (not the persistence
 * plugin's pool), so the read side is independent of the write side. Every handler write is
 * idempotent under at-least-once redelivery: upserts keyed by id, absolute `score`/`fav_count`,
 * read-current-delta `post_count` maintenance, and status-guarded delete/restore count adjustments.
 */
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
      status: String
  ): Future[Unit] =
    update(
      """UPDATE posts SET
        |  width = $2, height = $3, duration = $4, phash = $5,
        |  derivatives = CAST($6 AS JSONB), status = $7
        |WHERE id = $1""".stripMargin,
      s =>
        val bound = s
          .bind(0, id)
          .bind(1, Integer.valueOf(width))
          .bind(2, Integer.valueOf(height))
        val withDuration = duration match
          case Some(ms) => bound.bind(3, java.lang.Long.valueOf(ms))
          case None => bound.bindNull(3, classOf[java.lang.Long])
        withDuration.bind(4, phash).bind(5, derivativesJson).bind(6, status)
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
  def deletePost(id: String): Future[Unit] =
    postStatusAndTags(id).flatMap {
      case Some((status, tags)) if status != "deleted" =>
        sequentially(tags)(decrementTagCount).flatMap(_ => setStatus(id, "deleted"))
      case _ => Future.successful(()) // already deleted or absent: no-op
    }

  /** Reverse of [[deletePost]]: re-add the tags to the counts, then set status active. */
  def restorePost(id: String): Future[Unit] =
    postStatusAndTags(id).flatMap {
      case Some((status, tags)) if status == "deleted" =>
        sequentially(tags)(incrementTagCount).flatMap(_ => setStatus(id, "active"))
      case _ => Future.successful(()) // already active or absent: no-op
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
      for
        _ <- update(
          "UPDATE posts SET tags = $2 WHERE id = $1",
          _.bind(0, id).bind(1, newTags.toArray)
        )
        _ <- sequentially(added)(incrementTagCount)
        _ <- sequentially(removed)(decrementTagCount)
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
      """SELECT id, tags, status, score, fav_count, rating, width, height, duration, parent_id
        |FROM posts WHERE id = $1""".stripMargin,
      _.bind(0, id)
    ) { row =>
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
        parentId = Option(row.get("parent_id", classOf[String]))
      )
    }.map(_.headOption)

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
      (value: A) => { promise.trySuccess(value); () },
      (err: Throwable) => { promise.tryFailure(err); () },
      () => { promise.trySuccess(null.asInstanceOf[A]); () }
    )
    promise.future
