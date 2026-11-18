package fi.ferry.messenger.model

import kotlinx.serialization.Serializable

/**
 * The account palette (DESIGN §3.4): fills are the same in both modes (all ≥ 4.99:1 with white text); "ink" is the
 * colour for account-coloured text and lightens in dark mode; "wash" is the fill at 12% (light) / 22% (dark).
 */
@Serializable
enum class AccountColor(val fill: Long, val darkInk: Long) {
    OCEAN(0xFF1F5FD6, 0xFF7FAAF7),
    EMBER(0xFFC2410C, 0xFFF59A6B),
    GRAPE(0xFF7A3FD1, 0xFFB897F5),
    TEAL(0xFF0F766E, 0xFF5FCFC3),
    BERRY(0xFFC2255C, 0xFFF27DA6),
    MOSS(0xFF4D7C0F, 0xFFA3CF5F),
    COCOA(0xFF8A5A2B, 0xFFD6A877),
    SLATE(0xFF475B78, 0xFFA9B8CF);

    /** "Ocean": the swatch's name in the editor's footer ("Ocean is used by Studio."). */
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * How the user tells their Instagram accounts apart (the iOS app's AccountIdentity.swift, DESIGN §3.1): name, colour
 * and a one-grapheme mark, plus the scope bar's order. Keyed by account ID and stored on this device only.
 */
@Serializable
data class AccountIdentity(val name: String, val color: AccountColor, val mark: String, val order: Int) {
    companion object {
        const val MAX_NAME_LENGTH = 16

        /**
         * Gives every account without an identity a default: its Instagram name (or @username if that name is taken),
         * the next unused palette colour, and the first unused letter of its name. Existing identities are kept.
         */
        fun withDefaults(existing: Map<String, AccountIdentity>, logins: List<BridgeAPI.Login>): Map<String, AccountIdentity> {
            val result = existing.toMutableMap()
            for (login in logins) {
                if (login.id in result) continue
                val taken = result.values.toList()
                val names = taken.map { it.name.lowercase() }.toSet()
                val name = listOf(login.displayName, login.profile?.username ?: login.id).map { it.take(MAX_NAME_LENGTH) }
                    .firstOrNull { it.lowercase() !in names } ?: login.id.takeLast(MAX_NAME_LENGTH)
                val color = AccountColor.entries.firstOrNull { c -> taken.none { it.color == c } }
                    ?: AccountColor.entries[taken.size % AccountColor.entries.size] // more than 8 accounts: reuse
                val marks = taken.map { it.mark.uppercase() }.toSet()
                val candidates = name.uppercase().filter { it.isLetter() }.map { it.toString() } + ('A'..'Z').map { it.toString() }
                val mark = candidates.firstOrNull { it !in marks } ?: "•"
                val order = (taken.maxOfOrNull { it.order } ?: -1) + 1
                result[login.id] = AccountIdentity(name, color, mark, order)
            }
            return result
        }

        /** The scope bar's order after a drag in Accounts: `ids` in their new order; identities not listed keep theirs. */
        fun reordered(identities: Map<String, AccountIdentity>, ids: List<String>): Map<String, AccountIdentity> {
            val result = identities.toMutableMap()
            ids.forEachIndexed { index, id -> result[id]?.let { result[id] = it.copy(order = index) } }
            return result
        }

        // Editing (O5, A2; DESIGN §3.1, §3.6 rule 1: no two accounts share a colour, mark or name)

        /** What stops `draft` from being saved for `loginID`, given every account's identity (its own included). */
        fun problems(draft: AccountIdentity, loginID: String, identities: Map<String, AccountIdentity>): List<Problem> {
            val others = identities.filterKeys { it != loginID }.values.sortedBy { it.order }
            val name = draft.name.trim()
            val problems = mutableListOf<Problem>()
            if (name.isEmpty()) problems += Problem.NameEmpty
            else others.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }?.let { problems += Problem.NameTaken(it.name) }
            if (draft.mark.isEmpty()) problems += Problem.MarkEmpty
            else others.firstOrNull { it.mark.uppercase() == draft.mark.uppercase() }?.let { problems += Problem.MarkTaken(it.name) }
            others.firstOrNull { it.color == draft.color }?.let { problems += Problem.ColorTaken(it.name) }
            return problems
        }

        /** The account already using `color`, if it isn't `loginID` (taken swatches show its mark, DESIGN O5). */
        fun owner(color: AccountColor, excluding: String, identities: Map<String, AccountIdentity>): AccountIdentity? =
            identities.entries.firstOrNull { it.key != excluding && it.value.color == color }?.value

        /** One grapheme: the last one typed, as a capital if it's a letter ("ß" uppercases to two letters: kept). */
        fun normalizedMark(text: String): String {
            val last = graphemes(text.trim()).lastOrNull() ?: return ""
            val upper = last.uppercase()
            return if (graphemes(upper).size == 1 && upper.length == last.length) upper else last
        }

        /**
         * Mark chips (DESIGN O5): the first distinct letters of the name, then a few emoji, minus marks already taken. No
         * camera: next to "Instagram" it was the one Instagram-like glyph in the app.
         */
        fun markSuggestions(name: String, taken: Set<String>): List<String> {
            val letters = mutableListOf<String>()
            for (letter in name.uppercase().filter { it.isLetter() }.map { it.toString() }) {
                if (letter !in letters && letter !in taken) letters += letter
            }
            return letters.take(3) + listOf("✦", "⛵️", "🌊").filter { it !in taken }
        }

        /** Name chips (DESIGN O5): the Instagram name, then common labels, minus names already taken. */
        fun nameSuggestions(instagramName: String?, taken: Set<String>): List<String> {
            val candidates = listOfNotNull(instagramName?.take(MAX_NAME_LENGTH)) + listOf("Personal", "Work", "Studio", "Shop", "Creator")
            val lowered = taken.map { it.lowercase() }.toSet()
            val result = mutableListOf<String>()
            for (name in candidates) if (name.lowercase() !in lowered && name !in result) result += name
            return result.take(4)
        }

        private fun graphemes(text: String): List<String> {
            val it = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
            val out = mutableListOf<String>()
            var start = it.first()
            var end = it.next()
            while (end != java.text.BreakIterator.DONE) {
                out += text.substring(start, end)
                start = end
                end = it.next()
            }
            return out
        }
    }

    sealed interface Problem {
        data object NameEmpty : Problem
        /** The other account's name. */
        data class NameTaken(val by: String) : Problem
        data object MarkEmpty : Problem
        data class MarkTaken(val by: String) : Problem
        data class ColorTaken(val by: String) : Problem

        val isAboutName: Boolean get() = this is NameEmpty || this is NameTaken
        val isAboutMark: Boolean get() = this is MarkEmpty || this is MarkTaken

        val message: String
            get() = when (this) {
                NameEmpty -> "Give this account a name."
                is NameTaken -> "$by already uses this name."
                MarkEmpty -> "Pick a letter or an emoji."
                is MarkTaken -> "$by already uses this mark."
                is ColorTaken -> "$by already uses this color."
            }
    }
}
