package me.cference.artemis.http

import me.cference.artemis.gc.OrphanSweepService
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.DefaultJsonProtocol.*
import spray.json.RootJsonFormat

import scala.concurrent.Future

/** `POST /admin/gc/orphan-sweep` response: the scanned/orphan/deleted blob counts. */
final case class OrphanSweepResponse(scanned: Int, orphans: Int, deleted: Int)

/** `POST /admin/gc/purge-deleted` response: how many soft-deleted posts the pass purged. */
final case class PurgeDeletedResponse(purged: Int)

object AdminGcJson:
  given RootJsonFormat[OrphanSweepResponse] = jsonFormat3(OrphanSweepResponse.apply)
  given RootJsonFormat[PurgeDeletedResponse] = jsonFormat1(PurgeDeletedResponse.apply)

/**
 * The service-wide GC admin surface (admin-gc spec): trigger the failed-upload orphan sweep and one
 * retention-purge pass on demand. Both wrap existing service methods injected as plain functions,
 * so the routes test with fakes — no DB/Apollo. There is no auth; the `/admin` prefix is
 * organizational only (single-user service).
 *
 * The orphan sweep's `dryRun` defaults to the safe `true`: an absent or empty body computes and
 * reports the plan without deleting; a real sweep requires an explicit `{"dryRun": false}`.
 */
final class AdminGcRoutes(
    orphanSweep: Boolean => Future[OrphanSweepService.SweepResult],
    purgeDeleted: () => Future[Int]
):

  import AdminGcJson.given
  import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*

  def routes: Route =
    pathPrefix("admin" / "gc") {
      concat(
        // POST /admin/gc/orphan-sweep with optional {dryRun} body (default true when absent/empty).
        (path("orphan-sweep") & post & entity(as[String])) { body =>
          onSuccess(orphanSweep(dryRunFrom(body))) { r =>
            complete(OrphanSweepResponse(r.scanned, r.orphans, r.deleted))
          }
        },
        // POST /admin/gc/purge-deleted — run one retention-gated auto-purge pass now.
        (path("purge-deleted") & post) {
          onSuccess(purgeDeleted()) { purged =>
            complete(PurgeDeletedResponse(purged))
          }
        }
      )
    }

  /**
   * Parse `dryRun` from an optional JSON body: an absent/empty body (or a body missing/mis-typing
   * `dryRun`) defaults to the safe `true`; only an explicit `{"dryRun": false}` disables the
   * dry-run.
   */
  private def dryRunFrom(body: String): Boolean =
    val trimmed = body.trim
    if trimmed.isEmpty then true
    else
      import spray.json.*
      // Total: malformed JSON, a non-object, or a missing/mis-typed `dryRun` all fall back to the
      // safe `true` rather than throwing (which would 500 instead of dry-running).
      scala.util
        .Try(trimmed.parseJson.asJsObject.fields.get("dryRun"))
        .toOption
        .flatten match
        case Some(JsBoolean(b)) => b
        case _ => true

object AdminGcRoutes:
  def apply(
      orphanSweep: Boolean => Future[OrphanSweepService.SweepResult],
      purgeDeleted: () => Future[Int]
  ): AdminGcRoutes =
    new AdminGcRoutes(orphanSweep, purgeDeleted)
