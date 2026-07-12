package me.cference.artemis.domain

import me.cference.artemis.domain.SavedSearchesCommand.*
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

/**
 * Pure-domain tests for the saved-searches aggregate (saved-searches spec, task 1.1): save / rename
 * / remove / list, unique names, and the deterministic save-existing (upsert) behavior. Exercises
 * `decide` (validation → events) and `evolve` (fold) with no Pekko/DB.
 */
final class SavedSearchesDomainSpec extends AnyWordSpec with Matchers with EitherValues:

  private val now = Instant.parse("2026-07-10T00:00:00Z")
  private def name(s: String) = SearchName.unsafe(s)

  private def decide(state: SavedSearchesState, cmd: SavedSearchesCommand) =
    SavedSearchesDomain.decide(state, cmd)

  /** Apply a command through decide+evolve, failing the test on a rejection. */
  private def apply(state: SavedSearchesState, cmd: SavedSearchesCommand): SavedSearchesState =
    decide(state, cmd).value.foldLeft(state)(SavedSearchesDomain.evolve)

  private val blank = SavedSearchesState.initial

  "SavedSearchesDomain — save & list" should {

    "save a named query and list it" in {
      val s = apply(blank, SaveSearch(name("cat videos"), "cat_ears is:video", now))
      s.searches shouldBe Vector(SavedSearch(name("cat videos"), "cat_ears is:video"))
    }

    "preserve insertion order across multiple saves" in {
      val s = List(
        SaveSearch(name("a"), "qa", now),
        SaveSearch(name("b"), "qb", now),
        SaveSearch(name("c"), "qc", now)
      ).foldLeft(blank)(apply)
      s.searches.map(_.name.value) shouldBe Vector("a", "b", "c")
    }

    "upsert on re-saving an existing name (update the query in place, keep position)" in {
      val s0 = List(SaveSearch(name("a"), "qa", now), SaveSearch(name("b"), "qb", now))
        .foldLeft(blank)(apply)
      val s1 = apply(s0, SaveSearch(name("a"), "qa-v2", now))
      s1.searches shouldBe Vector(
        SavedSearch(name("a"), "qa-v2"),
        SavedSearch(name("b"), "qb")
      )
    }

    "treat an identical re-save as a no-op (zero events)" in {
      val s0 = apply(blank, SaveSearch(name("a"), "qa", now))
      decide(s0, SaveSearch(name("a"), "qa", now)).value shouldBe Seq.empty
    }
  }

  "SavedSearchesDomain — rename" should {

    "rename an existing search to a free name" in {
      val s0 = apply(blank, SaveSearch(name("old"), "q", now))
      val s1 = apply(s0, RenameSearch(name("old"), name("new"), now))
      s1.searches shouldBe Vector(SavedSearch(name("new"), "q"))
    }

    "reject renaming a search that does not exist" in {
      decide(blank, RenameSearch(name("nope"), name("x"), now)).left.value shouldBe
        DomainError.SavedSearchNotFound
    }

    "reject renaming onto a name already taken" in {
      val s0 = List(SaveSearch(name("a"), "qa", now), SaveSearch(name("b"), "qb", now))
        .foldLeft(blank)(apply)
      decide(s0, RenameSearch(name("a"), name("b"), now)).left.value shouldBe
        DomainError.SavedSearchNameConflict
    }

    "treat renaming to the same name as a no-op" in {
      val s0 = apply(blank, SaveSearch(name("a"), "qa", now))
      decide(s0, RenameSearch(name("a"), name("a"), now)).value shouldBe Seq.empty
    }
  }

  "SavedSearchesDomain — remove" should {

    "remove an existing search" in {
      val s0 = List(SaveSearch(name("a"), "qa", now), SaveSearch(name("b"), "qb", now))
        .foldLeft(blank)(apply)
      val s1 = apply(s0, RemoveSearch(name("a"), now))
      s1.searches.map(_.name.value) shouldBe Vector("b")
    }

    "treat removing an absent search as a no-op (zero events)" in {
      decide(blank, RemoveSearch(name("ghost"), now)).value shouldBe Seq.empty
    }
  }

  "SearchName.from" should {
    "reject an empty name" in {
      SearchName.from("   ").left.value shouldBe a[DomainError.InvalidSearchName]
    }
    "reject an over-long name" in {
      SearchName.from("x" * (SearchName.MaxLength + 1)).left.value shouldBe
        a[DomainError.InvalidSearchName]
    }
    "trim and accept a valid name" in {
      SearchName.from("  cat videos  ").value.value shouldBe "cat videos"
    }
  }
