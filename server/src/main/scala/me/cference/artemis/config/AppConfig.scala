package me.cference.artemis.config

import com.typesafe.config.Config

import scala.concurrent.duration.*

/**
 * PostgreSQL connection parameters for the read-side [[me.cference.artemis.projection]] repository,
 * which uses its own short-lived r2dbc connections rather than the persistence plugin's pool. Read
 * from the same `pekko.persistence.r2dbc.connection-factory` block as the journal so there is one
 * source of truth for the database coordinates.
 */
final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    connectTimeout: FiniteDuration
)

object AppConfig:

  def postgres(config: Config): PostgresConfig =
    val cf = config.getConfig("pekko.persistence.r2dbc.connection-factory")
    PostgresConfig(
      host = cf.getString("host"),
      port = cf.getInt("port"),
      database = cf.getString("database"),
      user = cf.getString("user"),
      password = cf.getString("password"),
      connectTimeout = cf.getDuration("connect-timeout").toMillis.millis
    )
