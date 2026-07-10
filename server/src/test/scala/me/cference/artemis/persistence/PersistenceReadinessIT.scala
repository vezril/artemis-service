package me.cference.artemis.persistence

import me.cference.artemis.config.PostgresConfig
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.testcontainers.utility.DockerImageName
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/**
 * Integration test for [[PersistenceReadiness]] (service-runtime spec — dependency readiness at
 * startup). The probe passes against a reachable Postgres (testcontainers) and fails fast, after a
 * bounded retry budget, against an unreachable one — so the boot aborts rather than serving a
 * half-wired process.
 */
final class PersistenceReadinessIT
    extends AnyWordSpec
    with Matchers
    with ForAllTestContainer
    with ScalaFutures:

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:16-alpine"),
    databaseName = "artemis",
    username = "artemis",
    password = "artemis"
  )

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(15, Seconds), interval = Span(200, Millis))

  private def cfg(host: String, port: Int) =
    PostgresConfig(
      host,
      port,
      container.databaseName,
      container.username,
      container.password,
      1.second
    )

  "PersistenceReadiness.check" should {

    "pass against a reachable Postgres" in {
      PersistenceReadiness
        .check(cfg(container.host, container.mappedPort(5432)))
        .futureValue shouldBe (())
    }

    "fail fast against an unreachable Postgres after exhausting retries" in {
      // Port 1 is never a Postgres; with a tiny budget the Future fails quickly rather than hanging.
      val result = PersistenceReadiness
        .check(cfg("127.0.0.1", 1), retries = 1, retryDelay = 50.millis)
        .failed
        .futureValue
      result shouldBe a[Throwable]
    }
  }
