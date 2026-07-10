package me.cference.artemis.persistence

import me.cference.artemis.config.PostgresConfig
import io.r2dbc.spi.{Connection, ConnectionFactories, ConnectionFactoryOptions}
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal

/**
 * Startup readiness probe for the PostgreSQL journal (service-runtime spec: dependency unavailable
 * at startup ⇒ fast, visible failure). Runs `SELECT 1` over a short-lived r2dbc connection with
 * bounded retries so an unreachable database aborts the boot rather than silently dropping writes
 * later. Mirrors apollo-storage's probe.
 */
object PersistenceReadiness:
  private val log = LoggerFactory.getLogger(getClass)

  def check(cfg: PostgresConfig, retries: Int = 5, retryDelay: FiniteDuration = 1.second)(using
      ec: ExecutionContext
  ): Future[Unit] =
    attempt(cfg).recoverWith {
      case NonFatal(ex) if retries > 0 =>
        log.warn(
          "Postgres not ready at {}:{} ({}); {} retries left",
          cfg.host,
          Integer.valueOf(cfg.port),
          ex.getMessage,
          Integer.valueOf(retries)
        )
        afterDelay(retryDelay)(check(cfg, retries - 1, retryDelay))
    }

  private def attempt(cfg: PostgresConfig)(using ec: ExecutionContext): Future[Unit] =
    val options = ConnectionFactoryOptions
      .builder()
      .option(ConnectionFactoryOptions.DRIVER, "postgresql")
      .option(ConnectionFactoryOptions.HOST, cfg.host)
      .option(ConnectionFactoryOptions.PORT, Integer.valueOf(cfg.port))
      .option(ConnectionFactoryOptions.DATABASE, cfg.database)
      .option(ConnectionFactoryOptions.USER, cfg.user)
      .option(ConnectionFactoryOptions.PASSWORD, cfg.password)
      .option(
        ConnectionFactoryOptions.CONNECT_TIMEOUT,
        java.time.Duration.ofMillis(cfg.connectTimeout.toMillis)
      )
      .build()

    val factory = ConnectionFactories.get(options)
    val validate = Mono
      .usingWhen[java.lang.Long, Connection](
        Mono.from[Connection](factory.create()),
        (conn: Connection) =>
          Mono.from(conn.createStatement("SELECT 1").execute()).flatMap { result =>
            Mono.from(result.map((_, _) => java.lang.Long.valueOf(1L)))
          },
        (conn: Connection) => conn.close()
      )
    toFuture(validate).map(_ => ())

  private def toFuture[A](mono: Mono[A]): Future[A] =
    val promise = Promise[A]()
    mono.subscribe(
      (value: A) => { promise.trySuccess(value); () },
      (err: Throwable) => { promise.tryFailure(err); () }
    )
    promise.future

  private def afterDelay[A](delay: FiniteDuration)(thunk: => Future[A])(using
      ec: ExecutionContext
  ): Future[A] =
    val promise = Promise[A]()
    val timer = new java.util.Timer(true)
    timer.schedule(
      new java.util.TimerTask:
        def run(): Unit =
          thunk.onComplete { result =>
            promise.tryComplete(result); timer.cancel()
          }
      ,
      delay.toMillis
    )
    promise.future
