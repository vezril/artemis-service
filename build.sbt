// ---------------------------------------------------------------------------
// Artemis — Danbooru-style, event-sourced media catalog (homelab-production).
// Third member of the Apollo/Hermes event-sourced family (Pekko-persistence
// journal + Pekko Projections into Postgres read tables).
//
// Modules (mirrors Apollo's split):
//   core   — pure domain (zero Pekko deps), exhaustively unit-tested.
//   server — Pekko runtime + persistence + projections + HTTP API (added M3+).
//
// Version is derived from git tags via sbt-dynver (no version literal committed).
// Only `core` exists today (roadmap M1); `server` lands with M3.
// ---------------------------------------------------------------------------

ThisBuild / scalaVersion := "3.3.4" // Scala 3 LTS
ThisBuild / organization := "me.cference.artemis"
ThisBuild / organizationName := "Artemis"
ThisBuild / licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / startYear := Some(2026)

// sbt-dynver: Docker-tag-safe separator (git describe's default '+' is illegal
// in image tags).
ThisBuild / dynverSeparator := "-"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror", // warnings are build failures (static-analysis)
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  // A non-exhaustive match is an error, not a warning: the domain-error→HTTP-status mapping and
  // the `evolve` folds rely on total matches, so a newly-added ADT case must fail the build rather
  // than degrade to a runtime MatchError / masked 500.
  "-Wconf:msg=match may not be exhaustive:e",
  "-source:3.3",
  "-Yretain-trees"
)

// SemanticDB so scalafix's semantic rules (DisableSyntax, OrganizeImports) run in CI
// (`scalafixAll --check`), not just locally.
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// Aligned versions so pekko-persistence-r2dbc (which pulls pekko 1.2.x / r2dbc
// 1.1.x) does not create a mixed-version classpath (Pekko forbids that).
lazy val pekkoVersion = "1.2.0"
lazy val pekkoR2dbcVersion = "1.1.0"
lazy val pekkoProjectionVersion = "1.1.0"
lazy val pekkoManagementVersion = "1.2.1"
lazy val prometheusVersion = "0.16.0"
lazy val scalaTestVersion = "3.2.19"
// Shared Codex wire contracts (the-lexicon): the Apollo gRPC client stubs and the
// codex.messages.v1 async messages. Pinned to a clean tagged release; consumed as
// published jars (no Apollo/object_api codegen runs here). Resolved from GitHub
// Packages in CI, or `~/.ivy2/local` after `sbt publishLocal` in the-lexicon.
lazy val lexiconVersion =
  "0.8.0" // +correlation_id envelope field + lexicon-common (CorrelationNames), request-tracing
lazy val testcontainersVersion = "0.41.4"
lazy val logbackVersion = "1.5.12"

// Security-driven overrides of transitive deps flagged HIGH by the release image scan (Trivy).
// Pekko/grpc pull older versions; these force patched ones (same compat line where possible). Kept
// as whole-suite bumps so netty/grpc/jackson modules never end up on mixed versions.
lazy val nettyVersion = "4.1.135.Final" // via pekko-http / pekko-grpc / grpc (was 4.1.112.Final)
lazy val grpcVersion =
  "1.75.0" // via pekko-grpc-runtime (was 1.67.1; shaded-netty CVE fixed in 1.75)
lazy val jacksonVersion =
  "2.21.5" // via pekko-serialization-jackson (was 2.19.2); annotations = 2.21

// read:packages token for the-lexicon GitHub Packages resolver: LEXICON_TOKEN (the CI secret,
// mirroring apollo-storage) preferred, else GITHUB_TOKEN (authorized dev). None ⇒ local ivy fallback.
lazy val lexiconToken: Option[String] =
  sys.env
    .get("LEXICON_TOKEN")
    .filter(_.nonEmpty)
    .orElse(sys.env.get("GITHUB_TOKEN").filter(_.nonEmpty))

lazy val commonSettings = Seq(
  Test / fork := true,
  Test / testForkedParallel := false,
  scalafmtOnCompile := false,
  // sbt delegates Compile → Test, so the base cranked flags reach test sources. In tests,
  // -Wnonunit-statement / -Wvalue-discard are noise that fights the ScalaTest DSL (every
  // `x shouldBe y` returns a discarded `Assertion`), so strip them from Test only.
  Test / scalacOptions --= Seq("-Wnonunit-statement", "-Wvalue-discard")
)

lazy val root = (project in file("."))
  .aggregate(core, server)
  .settings(
    name := "artemis",
    publish / skip := true
  )

// --- core: pure domain, no Pekko. -----------------------------------------
lazy val core = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "artemis-core",
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test,
      "org.scalacheck" %% "scalacheck" % "1.18.1" % Test
    )
  )

// --- server: Pekko runtime + persistence + projections + HTTP API. --------
// Event-sourced entities on the PostgreSQL r2dbc journal, read-model projections,
// the async media spine, and the HTTP API — assembled into a runnable, Dockerized
// service by `me.cference.artemis.Main` (roadmap M9).
lazy val server = (project in file("server"))
  .dependsOn(core)
  .enablePlugins(JavaAppPackaging, DockerPlugin, BuildInfoPlugin)
  .settings(commonSettings)
  .settings(
    name := "artemis-server",
    Compile / mainClass := Some("me.cference.artemis.Main"),
    // Fork `run` so the forked JVM stays alive on the ActorSystem's non-daemon threads
    // (an un-forked `sbt run` reaps the JVM the moment `main` returns from the async bind,
    // before the readiness gate resolves). The packaged image runs the main class directly,
    // where this is moot.
    Compile / run / fork := true,
    Compile / run / connectInput := true,
    // Put the repo-root `ddl/` on the test classpath so integration tests apply the
    // journal schema via `Source.fromResource` (the runtime r2dbc plugin does not
    // auto-create tables). Matches the sibling services' test-resource approach.
    Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "ddl",
    // Shared-contract resolver: GitHub Packages for the-lexicon jars. Added only when a token is
    // present — LEXICON_TOKEN (a read:packages PAT, the CI secret, mirroring apollo-storage) or
    // GITHUB_TOKEN (authorized dev). Absent a token, the pinned versions resolve from `~/.ivy2/local`
    // after `sbt publishLocal` in the-lexicon (the documented local fallback). No credentials committed.
    resolvers ++= lexiconToken
      .map(_ => "Lexicon GitHub Packages".at("https://maven.pkg.github.com/vezril/the-lexicon"))
      .toSeq,
    credentials ++= lexiconToken
      .map(token => Credentials("GitHub Package Registry", "maven.pkg.github.com", "vezril", token))
      .toSeq,
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
      // Clustering (service-runtime spec): membership, sharded entities (single writer
      // per id), split-brain resolution, and config-discovery cluster formation.
      "org.apache.pekko" %% "pekko-cluster-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion,
      "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion,
      // Align pekko-discovery (pulled transitively by management/grpc) with pekkoVersion;
      // Pekko forbids a mixed-artifact-version classpath.
      "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
      // Shared Codex wire contracts (the-lexicon): Apollo gRPC client, async messages, and the
      // HermesMQ PubSub gRPC client (the media-job transport).
      "io.codex" %% "lexicon-grpc" % lexiconVersion,
      "io.codex" %% "lexicon-messages" % lexiconVersion,
      "io.codex" %% "lexicon-hermes-grpc" % lexiconVersion,
      // Shared request-correlation names (CorrelationNames) — dependency-free constants.
      "io.codex" %% "lexicon-common" % lexiconVersion,
      // Catalog REST/JSON API (roadmap M6/M7).
      "org.apache.pekko" %% "pekko-http" % pekkoVersion,
      "org.apache.pekko" %% "pekko-http-spray-json" % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-r2dbc" % pekkoR2dbcVersion,
      // Read-side projections (roadmap M4): fold the journal into query tables.
      "org.apache.pekko" %% "pekko-projection-r2dbc" % pekkoProjectionVersion,
      "org.apache.pekko" %% "pekko-projection-eventsourced" % pekkoProjectionVersion,
      // Postgres r2dbc driver (explicit since r2dbc 1.1.0).
      "org.postgresql" % "r2dbc-postgresql" % "1.0.7.RELEASE",
      // Prometheus metrics (service-metrics spec): app CollectorRegistry, JVM collectors,
      // text exposition — mirrors the sibling services' /metrics surface.
      "io.prometheus" % "simpleclient" % prometheusVersion,
      "io.prometheus" % "simpleclient_hotspot" % prometheusVersion,
      "io.prometheus" % "simpleclient_common" % prometheusVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      // Structured JSON logging (constellation observability / Loki): the `json` appender's encoder.
      "net.logstash.logback" % "logstash-logback-encoder" % "8.0",
      // test
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoVersion % Test,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % testcontainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersVersion % Test,
      // JDBC driver used by tests to apply DDL and assert journal rows.
      "org.postgresql" % "postgresql" % "42.7.13" % Test
    ),
    // Security overrides: force patched versions of transitive deps the release image scan (Trivy)
    // flags HIGH. Whole-suite bumps so no module is left on a mismatched version.
    dependencyOverrides ++=
      Seq(
        "netty-buffer",
        "netty-codec",
        "netty-codec-dns",
        "netty-codec-http",
        "netty-codec-socks",
        "netty-common",
        "netty-handler",
        "netty-handler-proxy",
        "netty-resolver",
        "netty-resolver-dns",
        "netty-resolver-dns-classes-macos",
        "netty-resolver-dns-native-macos",
        "netty-transport",
        "netty-transport-classes-epoll",
        "netty-transport-native-epoll",
        "netty-transport-native-unix-common"
      ).map("io.netty" % _ % nettyVersion) ++
        Seq(
          "grpc-api",
          "grpc-context",
          "grpc-core",
          "grpc-netty-shaded",
          "grpc-protobuf",
          "grpc-protobuf-lite",
          "grpc-stub",
          "grpc-util"
        ).map("io.grpc" % _ % grpcVersion) ++
        Seq(
          "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
          "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
          "com.fasterxml.jackson.core" % "jackson-annotations" % "2.21", // annotations lags: no patch releases

          "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
          "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
          "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,
          "com.fasterxml.jackson.module" % "jackson-module-parameter-names" % jacksonVersion,
          "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,
          // The lz4-java on the classpath is the maintained `at.yawk.lz4` fork; bump it past its
          // HIGH CVE. (Also pin the original `org.lz4` coordinate in case it's pulled elsewhere.)
          "at.yawk.lz4" % "lz4-java" % "1.11.1",
          "org.lz4" % "lz4-java" % "1.8.1"
        ),
    // BuildInfo exposes the dynver version to the running app (health endpoint reports it).
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "me.cference.artemis.build",
    buildInfoOptions += BuildInfoOption.ToJson,
    // --- Docker (sbt-native-packager) — service-image spec. ---
    // Pin the Ubuntu 24.04 LTS ("noble") JRE variant. The default `21-jre` tag now resolves to an
    // Ubuntu 26.04 base that bundles Canonical's Go-based `/usr/bin/pebble`, whose golang.org/x/net
    // + Go-stdlib HIGH CVEs fail the release image scan (Trivy). `noble` ships no pebble and scans
    // clean, and Artemis (a JVM service) never used it.
    dockerBaseImage := "eclipse-temurin:21-jre-noble",
    dockerRepository := sys.env.get("DOCKERHUB_USERNAME"),
    dockerUpdateLatest := false, // the release workflow controls :latest explicitly
    Docker / packageName := "artemis",
    dockerExposedPorts := Seq(8080),
    // LOG_FORMAT=json is the image default (add-structured-logging); a local run stays text.
    dockerEnvVars := Map("HTTP_PORT" -> "8080", "LOG_FORMAT" -> "json"),
    // Non-root user (packager default UID 1001).
    Docker / daemonUserUid := Some("1001"),
    Docker / daemonUser := "artemis",
    // HEALTHCHECK uses bash's /dev/tcp so no extra packages (wget/curl) are needed in the
    // JRE base image. Exec form keeps the whole script as one arg to `bash -c`; bash
    // expands the HTTP_PORT override.
    dockerCommands ++= Seq(
      com.typesafe.sbt.packager.docker.Cmd(
        "HEALTHCHECK",
        "--interval=10s --timeout=3s --start-period=20s --retries=5 CMD " +
          """["bash","-c","exec 3<>/dev/tcp/127.0.0.1/${HTTP_PORT:-8080}; """ +
          """printf 'GET /health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3; """ +
          """grep -q '200 OK' <&3"]"""
      )
    )
  )
