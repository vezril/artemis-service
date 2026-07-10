package me.cference.artemis.tags

import io.r2dbc.spi.{
  Connection,
  ConnectionFactories,
  ConnectionFactory,
  ConnectionFactoryOptions,
  Row
}
import me.cference.artemis.config.PostgresConfig
import me.cference.artemis.domain.{Tag, TagGraph}
import reactor.core.publisher.{Flux, Mono}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters.*

/**
 * Loads the tag relationship graph from PostgreSQL for the write-path canonicalization pipeline.
 * Reads the DIRECT edges out of `tag_aliases` and `tag_implications` into a [[TagGraph]]; the
 * transitive closure (alias-chain resolution + transitive implication expansion, cycle-safe) is the
 * pure [[me.cference.artemis.domain.TagCanonicalization]] pipeline's job, so nothing here walks or
 * stores a closure.
 *
 * Uses its own short-lived r2dbc connections (mirroring
 * [[me.cference.artemis.projection.ReadModelRepository]]), independent of the persistence plugin's
 * pool. Names round-trip through [[Tag.unsafe]]: these rows originate from the validated write
 * path, so they are already canonical and skip re-validation.
 *
 * The tag graph changes rarely but is consulted on every tag edit, so production injects a
 * periodically-refreshed snapshot (`() => cachedGraph`) into the write path rather than calling
 * [[loadGraph]] per edit. That cache/refresh loop is a later milestone (M9); this repo only exposes
 * the one-shot load.
 */
final class TagGraphRepository(cfg: PostgresConfig)(using ec: ExecutionContext):

  private val factory: ConnectionFactory =
    ConnectionFactories.get(
      ConnectionFactoryOptions
        .builder()
        .option(ConnectionFactoryOptions.DRIVER, "postgresql")
        .option(ConnectionFactoryOptions.HOST, cfg.host)
        .option(ConnectionFactoryOptions.PORT, Integer.valueOf(cfg.port))
        .option(ConnectionFactoryOptions.DATABASE, cfg.database)
        .option(ConnectionFactoryOptions.USER, cfg.user)
        .option(ConnectionFactoryOptions.PASSWORD, cfg.password)
        .build()
    )

  /**
   * Read all alias and implication edges into a [[TagGraph]]: `tag_aliases` becomes a `Tag -> Tag`
   * map (one terminal-facing consequent per antecedent), and `tag_implications` groups consequents
   * by antecedent into `Tag -> Set[Tag]` (direct edges only).
   */
  def loadGraph(): Future[TagGraph] =
    for
      aliasRows <- query("SELECT antecedent, consequent FROM tag_aliases") { row =>
        Tag.unsafe(row.get("antecedent", classOf[String])) ->
          Tag.unsafe(row.get("consequent", classOf[String]))
      }
      implicationRows <- query("SELECT antecedent, consequent FROM tag_implications") { row =>
        Tag.unsafe(row.get("antecedent", classOf[String])) ->
          Tag.unsafe(row.get("consequent", classOf[String]))
      }
    yield
      // `.toMap` is last-wins on duplicate antecedents — safe because `tag_aliases.antecedent` is
      // the primary key, so there is exactly one consequent per antecedent by construction.
      val aliases = aliasRows.toMap
      val implications = implicationRows
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap
      TagGraph(aliases, implications)

  // --- r2dbc plumbing (mirrors ReadModelRepository) --------------------------

  private def query[A](sql: String)(map: Row => A): Future[Seq[A]] =
    withConnection { conn =>
      Flux
        .from(conn.createStatement(sql).execute())
        .flatMap(result => result.map((row, _) => map(row)))
        .collectList()
        .map(_.asScala.toVector)
    }

  private def withConnection[A](use: Connection => Mono[A]): Future[A] =
    toFuture(
      Mono.usingWhen[A, Connection](
        Mono.from[Connection](factory.create()),
        (conn: Connection) => use(conn),
        (conn: Connection) => conn.close()
      )
    )

  private def toFuture[A](mono: Mono[A]): Future[A] =
    val promise = Promise[A]()
    mono.subscribe(
      (value: A) => { val _ = promise.trySuccess(value) },
      (err: Throwable) => { val _ = promise.tryFailure(err) },
      () => {
        val _ = promise.trySuccess(null.asInstanceOf[A])
      } // scalafix:ok DisableSyntax.null
    )
    promise.future
