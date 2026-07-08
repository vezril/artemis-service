// Version derived from git tags — no version literal in source.
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.0")

// Formatting (CI runs scalafmtCheck).
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")

// Static analysis / linting.
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.13.0")
