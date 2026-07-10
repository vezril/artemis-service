package me.cference.artemis.domain

import me.cference.artemis.domain.PoolCommand.*
import me.cference.artemis.domain.PoolEvent.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

final class PoolDomainSpec extends AnyWordSpec with Matchers:

  private val now = Instant.parse("2026-07-08T00:00:00Z")
  private val poolId = PoolId.unsafe("pool|1")
  private val name = PoolName.unsafe("summer")
  private val postA = PostId.unsafe("post|a")
  private val postB = PostId.unsafe("post|b")
  private val postC = PostId.unsafe("post|c")

  private def poolWith(posts: PostId*): PoolState =
    PoolDomain.replay(PoolCreated(poolId, name, now) +: posts.map(PostAdded.apply(_, now)))

  "PoolDomain" should {

    "create a pool from Empty and fold to Active with no posts" in {
      val Right(events) =
        PoolDomain.decide(PoolState.Empty, CreatePool(poolId, name, now)): @unchecked
      events shouldBe Seq(PoolCreated(poolId, name, now))
      PoolDomain.replay(events) shouldBe PoolState.Active(poolId, name, Vector.empty)
    }

    "reject a duplicate CreatePool with zero events" in {
      PoolDomain.decide(poolWith(), CreatePool(poolId, name, now)) shouldBe Left(
        DomainError.PoolAlreadyExists
      )
    }

    "reject any command on a non-existent pool" in {
      PoolDomain.decide(PoolState.Empty, AddPost(postA, now)) shouldBe Left(
        DomainError.PoolNotFound
      )
    }

    "add a post and keep ordered membership" in {
      val state = poolWith(postA, postB)
      val Right(events) = PoolDomain.decide(state, AddPost(postC, now)): @unchecked
      events shouldBe Seq(PostAdded(postC, now))
      PoolDomain.replay(
        Seq(PoolCreated(poolId, name, now), PostAdded(postA, now), PostAdded(postB, now)) ++ events
      ) shouldBe PoolState.Active(poolId, name, Vector(postA, postB, postC))
    }

    "treat adding a duplicate post as an idempotent no-op" in {
      val state = poolWith(postA)
      PoolDomain.decide(state, AddPost(postA, now)) shouldBe Right(Seq.empty)
    }

    "remove a member post and fold it out of the order" in {
      val state = poolWith(postA, postB, postC)
      val Right(events) = PoolDomain.decide(state, RemovePost(postB, now)): @unchecked
      events shouldBe Seq(PostRemoved(postB, now))
      PoolDomain.replay(
        Seq(
          PoolCreated(poolId, name, now),
          PostAdded(postA, now),
          PostAdded(postB, now),
          PostAdded(postC, now)
        ) ++ events
      ) shouldBe PoolState.Active(poolId, name, Vector(postA, postC))
    }

    "reject removing a post that is not a member with zero events" in {
      val state = poolWith(postA, postB)
      PoolDomain.decide(state, RemovePost(postC, now)) shouldBe Left(DomainError.PostNotInPool)
    }

    "reorder membership and fold the new sequence" in {
      val state = poolWith(postA, postB, postC)
      val Right(events) =
        PoolDomain.decide(state, Reorder(Vector(postC, postA, postB), now)): @unchecked
      events shouldBe Seq(Reordered(Vector(postC, postA, postB), now))
      events.foldLeft(state)(PoolDomain.evolve) shouldBe
        PoolState.Active(poolId, name, Vector(postC, postA, postB))
    }

    "reject a reorder whose set differs from current membership" in {
      val state = poolWith(postA, postB, postC)
      PoolDomain.decide(state, Reorder(Vector(postC, postA), now)) shouldBe
        Left(DomainError.PostNotInPool)
    }

    "rename a pool and fold the new name" in {
      val state = poolWith(postA)
      val newName = PoolName.unsafe("winter")
      val Right(events) = PoolDomain.decide(state, RenamePool(newName, now)): @unchecked
      events shouldBe Seq(PoolRenamed(newName, now))
      events.foldLeft(state)(PoolDomain.evolve) shouldBe
        PoolState.Active(poolId, newName, Vector(postA))
    }

    "delete a pool, folding to Deleted, and reject subsequent commands" in {
      val state = poolWith(postA, postB)
      val Right(events) = PoolDomain.decide(state, DeletePool(now)): @unchecked
      events shouldBe Seq(PoolDeleted(now))
      val deleted = events.foldLeft(state)(PoolDomain.evolve)
      deleted shouldBe a[PoolState.Deleted]
      PoolDomain.decide(deleted, AddPost(postC, now)) shouldBe Left(DomainError.PoolNotFound)
    }
  }
