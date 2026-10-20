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
    SLATE(0xFF475B78, 0xFFA9B8CF),
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
    }
}
