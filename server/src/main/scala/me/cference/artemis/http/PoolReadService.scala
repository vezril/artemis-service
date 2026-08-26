package me.cference.artemis.http

import me.cference.artemis.projection.ReadModelRepository

import scala.concurrent.{ExecutionContext, Future}

/**
 * Orchestrates the two projection-backed pool reads: decode the opaque keyset cursor, run the
 * read-model query for `limit + 1` rows, hydrate/shape the DTO, and emit `nextCursor` from the
 * retained tail. A bad cursor is a `Left(message)` the route turns into a `400` — never a `500`.
 *
 * Injected into [[SearchRoutes]] (composed before [[CatalogRoutes]]) so `GET /pools` and `GET
 * /pools/{id}/posts` are claimed ahead of the entity `GET /pools/{id}`.
 */
final class PoolReadService(readModel: ReadModelRepository)(using ec: ExecutionContext):

  import PoolReadService.*

  /** `GET /pools` — one keyset page of pool cards (name-ordered) with visible counts + covers. */
  def listPools(cursor: Option[String], limit: Int): Future[Either[String, PoolListResponse]] =
    val capped = clamp(limit)
    decodeList(cursor) match
      case Left(err) => Future.successful(Left(err))
      case Right(after) =>
        readModel.listPools(after, capped).flatMap { rows =>
          val page = rows.take(capped)
          val hasMore = rows.sizeIs > capped
          readModel.poolCovers(page.map(_._1)).map { covers =>
            val pools = page.map { (id, name, count) =>
              PoolSummary(id, name, count, covers.get(id).map(SearchJson.summaryOf))
            }.toList
            val next =
              if hasMore then
                page.lastOption.map((id, name, _) => PoolListCursor(name.toLowerCase, id).encode)
              else None
            Right(PoolListResponse(pools, next))
          }
        }

  /** `GET /pools/{id}/posts` — one keyset page of hydrated members in pool order. */
  def poolPosts(
      poolId: String,
      cursor: Option[String],
      limit: Int
  ): Future[Either[String, SearchResponse]] =
    val capped = clamp(limit)
    decodeMembers(cursor) match
      case Left(err) => Future.successful(Left(err))
      case Right(after) =>
        readModel.poolPostsHydrated(poolId, after, capped).map { rows =>
          val page = rows.take(capped)
          val hasMore = rows.sizeIs > capped
          val posts = page.map((_, row) => SearchJson.summaryOf(row)).toList
          val next =
            if hasMore then page.lastOption.map((pos, row) => PoolPostsCursor(pos, row.id).encode)
            else None
          Right(SearchResponse(posts, next))
        }

object PoolReadService:
  /** Default page size when the request omits `limit=`, and the ceiling it is clamped to. */
  val DefaultPageSize: Int = 40
  private val MaxPageSize: Int = 100

  private def clamp(limit: Int): Int = math.max(1, math.min(limit, MaxPageSize))

  private def decodeList(cursor: Option[String]): Either[String, Option[(String, String)]] =
    cursor match
      case None => Right(None)
      case Some(s) => PoolListCursor.decode(s).map(c => Some((c.lowerName, c.id)))

  private def decodeMembers(cursor: Option[String]): Either[String, Option[(Int, String)]] =
    cursor match
      case None => Right(None)
      case Some(s) => PoolPostsCursor.decode(s).map(c => Some((c.position, c.postId)))
