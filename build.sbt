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
  "-Wunused:all",
  "-Wvalue-discard",
  // A non-exhaustive match is an error, not a warning: the domain-error→HTTP-status mapping and
  // the `evolve` folds rely on total matches, so a newly-added ADT case must fail the build rather
  // than degrade to a runtime MatchError / masked 500.
  "-Wconf:msg=match may not be exhaustive:e",
  "-source:3.3",
  "-Yretain-trees"
)

// Aligned versions so pekko-persistence-r2dbc (which pulls pekko 1.2.x / r2dbc
// 1.1.x) does not create a mixed-version classpath (Pekko forbids that).
lazy val pekkoVersion = "1.2.0"
lazy val pekkoR2dbcVersion = "1.1.0"
lazy val pekkoProjectionVersion = "1.1.0"
lazy val scalaTestVersion = "3.2.19"
lazy val testcontainersVersion = "0.41.4"
lazy val logbackVersion = "1.5.12"

lazy val commonSettings = Seq(
  Test / fork := true,
  Test / testForkedParallel := false,
  scalafmtOnCompile := false
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

// --- server: Pekko runtime + persistence (roadmap M3). --------------------
// Event-sourced entities on the PostgreSQL r2dbc journal. HTTP/gRPC/projections
// land in later milestones (M4+); this module is the durable write side.
lazy val server = (project in file("server"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "artemis-server",
    // Put the repo-root `ddl/` on the test classpath so integration tests apply the
    // journal schema via `Source.fromResource` (the runtime r2dbc plugin does not
    // auto-create tables). Matches the sibling services' test-resource approach.
    Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "ddl",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
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
      "ch.qos.logback" % "logback-classic" % logbackVersion,
      // test
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion % Test,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoVersion % Test,
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-scalatest" % testcontainersVersion % Test,
      "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersVersion % Test,
      // JDBC driver used by tests to apply DDL and assert journal rows.
      "org.postgresql" % "postgresql" % "42.7.4" % Test
    )
  )
