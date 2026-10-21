package fi.ferry.messenger.model

import fi.ferry.messenger.model.AccountIdentity.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The O5/A2 editor's rules (DESIGN §3.1, §3.6 rule 1): no two accounts share a name, colour or mark. */
class IdentityTests {
    private val ids = mapOf(
        "studio" to AccountIdentity("Studio", AccountColor.OCEAN, "S", 0),
        "personal" to AccountIdentity("Personal", AccountColor.EMBER, "P", 1),
    )

    @Test fun unchangedIdentityHasNoProblems() {
        assertEquals(emptyList<Problem>(), AccountIdentity.problems(ids.getValue("studio"), "studio", ids))
    }

    /** Its own name, colour and mark never count as taken; another account's do, ignoring case and stray spaces. */
    @Test fun anotherAccountsNameMarkAndColorAreTaken() {
        val draft = ids.getValue("studio").copy(name = " personal ", mark = "p", color = AccountColor.EMBER)
        assertEquals(listOf(Problem.NameTaken("Personal"), Problem.MarkTaken("Personal"), Problem.ColorTaken("Personal")),
            AccountIdentity.problems(draft, "studio", ids))
        // The same values for Personal itself are fine.
        assertEquals(emptyList<Problem>(), AccountIdentity.problems(draft, "personal", ids))
    }

    @Test fun emptyNameAndMarkCantBeSaved() {
        val draft = AccountIdentity("   ", AccountColor.GRAPE, "", 2)
        assertEquals(listOf(Problem.NameEmpty, Problem.MarkEmpty), AccountIdentity.problems(draft, "new", ids))
        assertEquals(listOf(Problem.NameEmpty), AccountIdentity.problems(draft, "new", ids).filter { it.isAboutName })
        assertEquals(listOf(Problem.MarkEmpty), AccountIdentity.problems(draft, "new", ids).filter { it.isAboutMark })
    }

    /** A taken swatch shows its owner's mark; your own colour has no "owner". */
    @Test fun colorOwner() {
        assertEquals("P", AccountIdentity.owner(AccountColor.EMBER, "studio", ids)?.mark)
        assertNull(AccountIdentity.owner(AccountColor.OCEAN, "studio", ids))
        assertNull(AccountIdentity.owner(AccountColor.GRAPE, "studio", ids))
    }

    @Test fun markIsOneGrapheme() {
        assertEquals("S", AccountIdentity.normalizedMark("s"))
        assertEquals("B", AccountIdentity.normalizedMark("ab")) // typing a second character replaces the first
        assertEquals("X", AccountIdentity.normalizedMark(" x "))
        assertEquals("🌊", AccountIdentity.normalizedMark("🌊"))
        assertEquals("👩🏽‍💻", AccountIdentity.normalizedMark("👩🏽‍💻")) // one grapheme, several code points
        assertEquals("ß", AccountIdentity.normalizedMark("ß")) // uppercases to "SS": keep the letter
        assertEquals("", AccountIdentity.normalizedMark(""))
    }

    /** No camera among the emoji: next to "Instagram" it was the one Instagram-like glyph (review 2026-10-15 #12). */
    @Test fun markSuggestionsSkipTakenMarks() {
        assertEquals(listOf("S", "T", "U", "✦", "⛵️", "🌊"), AccountIdentity.markSuggestions("Studio", emptySet()))
        assertEquals(listOf("T", "U", "D", "✦", "🌊"), AccountIdentity.markSuggestions("Studio", setOf("S", "⛵️")))
        assertEquals(listOf("✦", "⛵️", "🌊"), AccountIdentity.markSuggestions("", emptySet()))
        assertEquals("⛵️", AccountIdentity.normalizedMark("⛵️")) // one grapheme (with its variation selector)
    }

    @Test fun nameSuggestionsStartWithTheInstagramNameAndSkipTakenOnes() {
        assertEquals(listOf("Kivo Studio", "Work", "Studio", "Shop"), AccountIdentity.nameSuggestions("Kivo Studio", setOf("personal")))
        assertEquals(listOf("Personal", "Work", "Studio", "Shop"), AccountIdentity.nameSuggestions(null, emptySet()))
    }

    /** A1 drag to reorder: the listed accounts take their new positions; anything else keeps its order. */
    @Test fun reorderSetsScopeBarOrder() {
        val all = ids + ("band" to AccountIdentity("Band", AccountColor.GRAPE, "B", 7))
        val moved = AccountIdentity.reordered(all, listOf("personal", "studio"))
        assertEquals(0, moved["personal"]?.order)
        assertEquals(1, moved["studio"]?.order)
        assertEquals(7, moved["band"]?.order)
        assertEquals("Studio", moved["studio"]?.name)
    }

    @Test fun colorLabel() {
        assertEquals("Ocean", AccountColor.OCEAN.label)
    }
}
