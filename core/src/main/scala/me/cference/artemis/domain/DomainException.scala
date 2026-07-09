package me.cference.artemis.domain

/**
 * Carries a typed [[DomainError]] as a `Throwable` so it can travel through a `StatusReply.error`
 * reply and be mapped to a transport status (e.g. a gRPC status code) at the edge, instead of
 * degrading to a bare message string.
 */
final case class DomainException(error: DomainError) extends RuntimeException(error.message)
