// Version derived from git tags — no version literal in source.
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")

// Docker image build (service-image spec: non-root, EXPOSE, HEALTHCHECK).
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.1")

// Build-time version info exposed to the app (health endpoint reports version).
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")

// Formatting (CI runs scalafmtCheck).
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

// Static analysis / linting.
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.13.0")

// Statement coverage reporting in CI (static-analysis). Ungated initially — report only,
// no `coverageMinimumStmtTotal` floor until the suite matures.
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.2.2")
