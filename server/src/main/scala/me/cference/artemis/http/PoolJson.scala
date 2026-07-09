package me.cference.artemis.http

import me.cference.artemis.domain.PoolState

/**
 * Renders a folded `PoolState` into the flat `PoolResponse` DTO served by `GET /pools/{id}`. Only
 * an `Active` pool has members; `Empty`/`Deleted` are mapped to a 404 by the caller before reaching
 * here (rendered defensively as an empty membership).
 */
object PoolJson:

  def render(id: String, state: PoolState): PoolResponse =
    state match
      case PoolState.Active(pid, name, posts) =>
        PoolResponse(pid.value, name.value, posts.map(_.value).toList)
      case PoolState.Deleted(pid, name) =>
        PoolResponse(pid.value, name.value, Nil)
      case PoolState.Empty =>
        PoolResponse(id, "", Nil)
