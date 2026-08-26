package me.cference.artemis.http

import org.apache.pekko.http.scaladsl.model.{
  ContentType,
  ContentTypes,
  HttpCharsets,
  HttpEntity,
  MediaType,
  StatusCodes
}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import scala.io.Source
import scala.util.Using

/**
 * The self-documenting API surface (service-docs spec): `GET /openapi.yaml` serves the hand-
 * authored OpenAPI 3 spec from classpath resources (the single source of truth, updated with every
 * API change), and `GET /docs` serves a minimal Swagger UI page rendering it.
 *
 * The UI page loads swagger-ui from the unpkg CDN — a deliberate trade-off: the spec itself is
 * fully self-hosted (curl-able, Insomnia-importable offline), while the interactive viewer needs
 * the browser to reach the CDN once (cached thereafter). Vendoring the ~1.5MB UI bundle into the
 * image wasn't worth it for a LAN console; revisit if offline docs ever matter.
 *
 * The spec is read once at startup (it is a build-time resource — a missing resource fails fast).
 */
final class DocsRoutes(spec: String):

  private val yamlType: ContentType.WithFixedCharset =
    MediaType.textWithFixedCharset("yaml", HttpCharsets.`UTF-8`).toContentType

  def routes: Route =
    concat(
      (path("openapi.yaml") & get) {
        complete(HttpEntity(yamlType, spec))
      },
      (path("docs") & get) {
        complete(
          StatusCodes.OK,
          HttpEntity(ContentTypes.`text/html(UTF-8)`, DocsRoutes.SwaggerHtml)
        )
      }
    )

object DocsRoutes:

  /** Load the spec from classpath resources; a missing resource is a fail-fast at wiring time. */
  def fromResources(): DocsRoutes =
    val spec = Using.resource(Source.fromResource("openapi.yaml"))(_.mkString)
    new DocsRoutes(spec)

  /**
   * A minimal Swagger UI host page. The spec URL is RELATIVE, so the page works both direct
   * (`/docs` → `openapi.yaml`) and through the artemis-ui BFF (`/api/artemis/docs` →
   * `/api/artemis/openapi.yaml`).
   */
  private[http] val SwaggerHtml: String =
    """<!DOCTYPE html>
      |<html lang="en">
      |<head>
      |  <meta charset="utf-8"/>
      |  <meta name="viewport" content="width=device-width, initial-scale=1"/>
      |  <title>Artemis API docs</title>
      |  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"/>
      |</head>
      |<body>
      |<div id="swagger-ui"></div>
      |<script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
      |<script>
      |  window.ui = SwaggerUIBundle({
      |    url: "openapi.yaml",
      |    dom_id: "#swagger-ui",
      |    deepLinking: true,
      |    defaultModelsExpandDepth: 0
      |  });
      |</script>
      |</body>
      |</html>
      |""".stripMargin
