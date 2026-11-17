package fi.ferry.messenger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure inbox rules (the iOS app's Inbox.swift, DESIGN §4): which chats show, where, in what order, with which counts. */
object Inbox {
    /** Whose chats you see: everyone's, or one account's (its FBID). */
    sealed interface Scope {
        /** "" for All, else the account ID (the Requests "seen" markers use the same keys). */
        val key: String

        data object All : Scope {
            override val key = ""
        }

        data class Account(val id: String) : Scope {
            override val key: String get() = id
        }
    }

    /** `CHATS` is the main list: Primary on professional accounts, everything on personal ones. */
    enum class Folder { CHATS, GENERAL, REQUESTS, ARCHIVED }

    /** The tag that archives a chat on this device; Instagram never hears of it. */
    const val ARCHIVED_TAG = "u.archived"
    /** When the chat was archived (ms), inside the tag. */
    const val ARCHIVED_AT_KEY = "com.igmessenger.archived_at"
    /** The tag that marks a chat unread: this device only, like Archive (Instagram's web API has no request for it). */
    const val MARKED_UNREAD_TAG = "u.unread"

    /**
     * The tag for archiving `room` now. Never earlier than its last message: a phone clock behind Instagram's would
     * otherwise date the archive before that message and bring the chat straight back.
     */
    fun archiveTag(room: Room, now: Long): JsonElement = buildJsonObject { put(ARCHIVED_AT_KEY, maxOf(now, room.lastActivity)) }

    /**
     * A chat shows when the engine made it (`m.bridge`) and, once the accounts are known (`logins` non-null), its
     * account is one of them, so a removed account's chats hide. A request being deleted hides until it's gone.
     */
    fun isVisible(room: Room, logins: Set<String>?): Boolean {
        val receiver = room.bridge?.receiver ?: return false
        if (room.requestAction == Room.RequestAction.DELETE) return false
        return logins?.contains(receiver) ?: true
    }

    /** Requests first, then this device's archive, then Instagram's tab (General only on professional accounts). */
    fun folder(of: Room): Folder {
        if (isRequest(of)) return Folder.REQUESTS
        // New activity after archiving brings the chat back without anyone touching the tag.
        of.tags[ARCHIVED_TAG]?.let { if (of.lastActivity <= (it[ARCHIVED_AT_KEY].long ?: Long.MAX_VALUE)) return Folder.ARCHIVED }
        return if (of.bridge?.folder == BridgeInfo.InboxFolder.GENERAL) Folder.GENERAL else Folder.CHATS
    }

    fun inScope(room: Room, scope: Scope): Boolean = when (scope) {
        Scope.All -> true
        is Scope.Account -> room.bridge?.receiver == scope.id
    }

    fun inScope(login: String, scope: Scope): Boolean = scope == Scope.All || scope == Scope.Account(login)

    /**
     * The General tile shows in a scope that holds a professional account (All included: it's the only way to those
     * chats there), or any General chat ("data wins" over an unknown account type).
     */
    fun showsGeneral(rooms: Iterable<Room>, scope: Scope, accounts: List<BridgeAPI.Login>, logins: Set<String>?): Boolean =
        accounts.any { it.isProfessional && inScope(it.id, scope) } || rooms(rooms, scope, Folder.GENERAL, logins).isNotEmpty()

    /** The rows of one list, newest activity first (ties by room ID, so the order never flickers). */
    fun rooms(rooms: Iterable<Room>, scope: Scope, folder: Folder, logins: Set<String>?): List<Room> =
        sorted(rooms.filter { isVisible(it, logins) && inScope(it, scope) && folder(it) == folder })

    /**
     * Chats in scope whose name contains the query, ignoring case and accents. Archived chats are found too (search
     * is a way back to them); requests stay in their own place.
     */
    fun search(rooms: Iterable<Room>, query: String, scope: Scope, logins: Set<String>?): List<Room> {
        val wanted = fold(query.trim(' ', '\t'))
        if (wanted.isEmpty()) return emptyList()
        return sorted(rooms.filter {
            isVisible(it, logins) && inScope(it, scope) && folder(it) != Folder.REQUESTS && wanted in fold(it.name)
        })
    }

    /** Case and accents folded away (Swift's `localizedStandardContains`). */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    private val MARKS = Regex("\\p{Mn}+")

    private fun sorted(rooms: List<Room>): List<Room> =
        rooms.map { it to it.lastActivity }.sortedWith(compareByDescending<Pair<Room, Long>> { it.second }.thenBy { it.first.id }).map { it.first }

    /** The contact's @handle without a lookup: a DM with another of *our* accounts (its channel ID is that account). */
    fun contactHandle(room: Room, accounts: List<BridgeAPI.Login>): String? {
        val bridge = room.bridge?.takeIf { it.isDM } ?: return null
        val other = bridge.channelID ?: return null
        return accounts.firstOrNull { it.id == other }?.profile?.username
    }

    /** The other person in a DM: the joined member who isn't us. */
    fun contactUserID(room: Room, me: String): String? {
        if (room.bridge?.isDM != true) return null
        return room.state["m.room.member"].orEmpty()
            .filter { it.key != me && it.value.content["membership"].string == "join" }
            .keys.sorted().firstOrNull()
    }

    // Members (Chat Info, groups)

    data class Member(
        /** The user's ID in the chat. */
        val id: String,
        /** "You" for us. */
        val name: String,
        val avatarURL: String? = null,
        val handle: String? = null,
        val isMe: Boolean,
    )

    /** Who's in the chat: its joined member events. Names fall back as the thread's do; sorted by name, you last. */
    fun members(of: Room, me: String, profiles: Map<String, UserProfile>): List<Member> {
        val collator = Collator.getInstance()
        return of.state["m.room.member"].orEmpty().filter { it.value.content["membership"].string == "join" }.keys
            .map { id ->
                if (id == me) Member(id, "You", isMe = true)
                else {
                    val profile = profiles[id]
                    Member(id, of.displayName(id, profile), of.avatarURL(id, profile), profile?.handle, isMe = false)
                }
            }
            .sortedWith { a, b ->
                when {
                    a.isMe != b.isMe -> if (a.isMe) 1 else -1
                    else -> collator.compare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
                }
            }
    }

    /** The two faces a group without its own picture shows, people with a picture first; none with fewer than two others. */
    fun faces(of: Room): List<Member> {
        val others = members(of, of.ownUserID ?: IGAdapter.ME, emptyMap()).filter { !it.isMe }
        if (others.size < 2) return emptyList()
        return (others.filter { it.avatarURL != null } + others.filter { it.avatarURL == null }).take(2)
    }

    /** People in the chat, us included: its joined member events. */
    fun memberCount(of: Room): Int = of.state["m.room.member"].orEmpty().count { it.value.content["membership"].string == "join" }

    // Start block (DESIGN T1)

    /** The top of a thread's history: who it's with, then (only while it's empty) which account you're writing from. */
    data class StartBlock(
        /** "4 members" in a group, "@handle" in a DM when it's known. */
        val subtitle: String?,
        val messagingFrom: String?,
    )

    fun startBlock(isGroup: Boolean, members: Int, handle: String?, account: String?, isEmpty: Boolean): StartBlock = StartBlock(
        subtitle = if (isGroup) (if (members > 0) "$members member${if (members == 1) "" else "s"}" else null) else handle?.let { "@$it" },
        messagingFrom = if (isEmpty) account?.let { "You're messaging from $it." } else null,
    )

    // Request bar (DESIGN T2)

    data class RequestTitle(val shown: String, val spoken: String)

    /** On screen the account's tag ends the title, so the words stop at "message"; TalkBack hears the account's name. */
    fun requestTitle(requester: String, account: String?): RequestTitle =
        if (account == null) RequestTitle("$requester wants to message you.", "$requester wants to message you.")
        else RequestTitle("$requester wants to message", "$requester wants to message $account.")

    // Account stack (DESIGN §3.5)

    /**
     * The inbox's accounts, side by side and never over each other (principle 9): all of them up to three, else the
     * first two and a "+N". A problem is a badge on its account's avatar, or on "+N" when that account is behind it.
     */
    class AccountStack(accounts: List<Account>) {
        enum class Status { NEEDS_YOU, CONNECTING }

        data class Account(val id: String, val name: String, val status: Status?) {
            constructor(id: String, name: String, health: BridgeAPI.Login.Health) :
                this(id, name, if (health.needsUser) Status.NEEDS_YOU else if (health.isBusy) Status.CONNECTING else null)
        }

        /** In Accounts order. */
        val shown: List<Account> = accounts.take(if (accounts.size <= 3) accounts.size else 2)
        val hidden: List<Account> = accounts.drop(shown.size)

        /** The "+N" badge: needs-you wins over connecting. */
        val hiddenStatus: Status?
            get() = when {
                hidden.any { it.status == Status.NEEDS_YOU } -> Status.NEEDS_YOU
                hidden.any { it.status == Status.CONNECTING } -> Status.CONNECTING
                else -> null
            }

        /** One element: "Accounts: Alex, Alex Studio, and 2 more. Alex Studio needs attention." */
        val accessibilityLabel: String
            get() {
                val all = shown + hidden
                if (all.isEmpty()) return "Accounts"
                val names = shown.map { it.name } + (if (hidden.isEmpty()) emptyList() else listOf("${hidden.size} more"))
                val needs = all.filter { it.status == Status.NEEDS_YOU }.map { it.name }
                val busy = all.filter { it.status == Status.CONNECTING }.map { it.name }
                return (listOf("Accounts: ${serial(names)}.") +
                    (if (needs.isEmpty()) emptyList() else listOf("${serial(needs)} ${if (needs.size == 1) "needs" else "need"} attention.")) +
                    (if (busy.isEmpty()) emptyList() else listOf("${serial(busy)} ${if (busy.size == 1) "is" else "are"} connecting.")))
                    .joinToString(" ")
            }

        /** "A", "A and B", "A, B, and C" (the review's wording keeps the serial comma). */
        private fun serial(names: List<String>): String = when (names.size) {
            0 -> ""
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> "${names.dropLast(1).joinToString(", ")}, and ${names.last()}"
        }
    }

    /** The subtitle in an account scope: "@handle", "@handle · Primary" when professional, just "Primary" beside three or more. */
    fun scopeSubtitle(handle: String?, isProfessional: Boolean, accounts: Int): String = when {
        handle == null -> ""
        !isProfessional -> "@$handle"
        accounts >= 3 -> "Primary"
        else -> "@$handle · Primary"
    }

    /** Our DM with `userID` on `account`, so a search result opens it without asking Instagram. Requests count too. */
    fun existingDM(userID: String, account: String, rooms: Iterable<Room>): Room? = rooms.firstOrNull { room ->
        val bridge = room.bridge
        bridge != null && bridge.isDM && bridge.receiver == account && bridge.channelID == userID && isVisible(room, null)
    }

    /** Unread chats in one list of the scope, the main list by default: requests, archive and General don't count there. */
    fun unreadChats(rooms: Iterable<Room>, scope: Scope, folder: Folder = Folder.CHATS, logins: Set<String>?): Int =
        rooms(rooms, scope, folder, logins).count { it.isUnread }

    /** Requests waiting in scope ("3 waiting" in the Folders menu). */
    fun requestCount(rooms: Iterable<Room>, scope: Scope, logins: Set<String>?): Int =
        rooms(rooms, scope, Folder.REQUESTS, logins).size

    // Folders menu and Requests card (D14)

    /**
     * Requests in scope that arrived (or got a message) after Requests was last opened in a scope that shows them: All,
     * or their own account. `seen` holds those local times (ms) by `Scope.key`. Nothing is sent to Instagram.
     */
    fun newRequests(rooms: Iterable<Room>, scope: Scope, logins: Set<String>?, seen: Map<String, Long>): List<Room> =
        rooms(rooms, scope, Folder.REQUESTS, logins).filter { room ->
            room.lastActivity > maxOf(seen[Scope.All.key] ?: Long.MIN_VALUE, room.bridge?.receiver?.let { seen[it] } ?: Long.MIN_VALUE)
        }

    /** The marker to store when Requests opens in `scope`: now, but never before the newest request there. */
    fun requestsSeenMarker(rooms: Iterable<Room>, scope: Scope, logins: Set<String>?, now: Long): Long =
        maxOf(now, rooms(rooms, scope, Folder.REQUESTS, logins).firstOrNull()?.lastActivity ?: Long.MIN_VALUE)

    /** The Folders menu for one scope: which items, their subtitles and the badge. */
    data class FoldersMenu(val items: List<Folder>, val requestsWaiting: Int, val newRequests: Int, val generalUnread: Int) {
        /** Hidden at 0. General's unread chats count; requests only until you've looked. */
        val badge: Int get() = newRequests + generalUnread

        /** "2 waiting", "1 unread"; nothing rather than "0". */
        fun subtitle(folder: Folder): String? = when {
            folder == Folder.REQUESTS && requestsWaiting > 0 -> "$requestsWaiting waiting"
            folder == Folder.GENERAL && generalUnread > 0 -> "$generalUnread unread"
            else -> null
        }

        /** What the badge counts, since the badge itself isn't read out. */
        val accessibilityLabel: String
            get() = listOfNotNull(
                "Folders",
                if (newRequests > 0) "$newRequests new request${if (newRequests == 1) "" else "s"}" else null,
                if (generalUnread > 0) "$generalUnread unread in General" else null,
            ).joinToString(", ")
    }

    fun foldersMenu(rooms: Collection<Room>, scope: Scope, accounts: List<BridgeAPI.Login>, logins: Set<String>?, seen: Map<String, Long>) =
        FoldersMenu(
            items = (if (showsGeneral(rooms, scope, accounts, logins)) listOf(Folder.GENERAL) else emptyList()) + listOf(Folder.REQUESTS, Folder.ARCHIVED),
            requestsWaiting = requestCount(rooms, scope, logins),
            newRequests = newRequests(rooms, scope, logins, seen).size,
            generalUnread = unreadChats(rooms, scope, Folder.GENERAL, logins),
        )

    fun requestsCardTitle(count: Int): String = "$count message request${if (count == 1) "" else "s"}"

    /** Names only: no message text, so spam never reaches the main list. */
    fun requestsCardLabel(names: List<String>): String = "${requestsCardTitle(names.size)}, from ${listed(names)}. Opens Requests."

    /** "A", "A and B", "A, B and C". */
    private fun listed(names: List<String>): String =
        if (names.size <= 1) names.firstOrNull().orEmpty() else "${names.dropLast(1).joinToString(", ")} and ${names.last()}"

    /**
     * Whether the UI may offer "Seen", typing, reactions, edits and unsends here: never in a request (they'd tell the
     * requester we read it), nor in a chat the engine didn't make. The engine enforces it too (core/chat rules.go);
     * this follows its flag, not our optimistic Accept.
     */
    fun allowsReceipts(room: Room): Boolean = room.bridge?.isRequest == false

    // Requests (DESIGN I2/T2)

    /** A request as the user sees it: the engine's flag, unless they just accepted (optimistic). */
    fun isRequest(room: Room): Boolean = room.bridge?.isRequest == true && room.requestAction != Room.RequestAction.ACCEPT

    /** What sits at the bottom of a thread (DESIGN T2, §7.2). */
    enum class ThreadBar {
        COMPOSER,
        /** Accept / Delete / "Reply instead" instead of the composer. */
        REQUEST,
        /** The composer, with "Replying accepts the request." (the engine accepts before sending). */
        REPLY_ACCEPTS,
        /** The account is signed out: "Log in again" instead of the composer. */
        SIGNED_OUT,
    }

    fun threadBar(room: Room, health: BridgeAPI.Login.Health?, replyingInstead: Boolean): ThreadBar = when {
        health == BridgeAPI.Login.Health.NeedsLogin -> ThreadBar.SIGNED_OUT
        !isRequest(room) -> ThreadBar.COMPOSER
        replyingInstead -> ThreadBar.REPLY_ACCEPTS
        else -> ThreadBar.REQUEST
    }

    /** DESIGN A3 reassurance: "Personal and Band keep working." */
    fun othersKeepWorking(names: List<String>): String? =
        if (names.isEmpty()) null else "${listed(names)} ${if (names.size == 1) "keeps" else "keep"} working."

    // Row content

    data class Preview(val glyph: Glyph?, val text: String)

    /** Line 2 of a chat row (DESIGN I1), from the thread's classifier, so rows and bubbles agree. */
    fun preview(of: Room, me: String, profiles: Map<String, UserProfile> = emptyMap()): Preview? {
        val message = of.lastMessage ?: return null
        val content = of.currentContent(message)
        val mine = message.sender == me
        // In groups, name the sender ("Theo: …"), or nobody rather than an ID; in DMs only our own messages get one.
        val who = if (mine) "You" else if (of.bridge?.isDM == false) {
            message.sender?.let { of.knownName(it, profiles[it]) }?.split(' ')?.firstOrNull { it.isNotEmpty() }
        } else null
        val prefix = who?.let { "$it: " }.orEmpty()

        val kind = MessageKind.of(message.type, content)
        content[MessageKind.SHARE_TEXT_KEY].string?.let { return Preview(null, prefix + it) } // a story reply or reaction
        val sent = when (kind) {
            is MessageKind.Share -> kind.type.phrase
            is MessageKind.Unsupported -> kind.what
            else -> null
        }
        if (sent != null) {
            val text = if (mine) "You sent $sent" else who?.let { "$it sent $sent" } ?: "Sent $sent"
            val author = ShareCaption.of(content).author?.let { " by @$it" }.orEmpty()
            return Preview(kind.media?.glyph, text + author)
        }
        kind.media?.let { return Preview(it.glyph, prefix + it.label) }
        val body = Timeline.body(content).lines().filter { it.isNotEmpty() }.joinToString(" ")
        return Preview(null, (if (kind == MessageKind.ChatEvent) "" else prefix) + body) // an event names who did it
    }

    /**
     * Patterns for the row's time, from the phone's settings (`android.text.format.DateFormat`: 12 or 24 hours, the
     * locale's order); the defaults are for tests.
     */
    data class TimeFormats(
        val time: String = "HH:mm",
        val weekday: String = "EEE",
        val date: String = "d MMM",
        val dateWithYear: String = "d MMM y",
        val locale: Locale = Locale.ROOT,
    )

    /** Row time: "9:41" today, "Yesterday", the weekday within a week, then a short date (with the year if not this one). */
    fun timeLabel(ts: Long, now: Long, zone: ZoneId, formats: TimeFormats = TimeFormats()): String {
        val date = Instant.ofEpochMilli(ts).atZone(zone)
        val day = date.toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val pattern = when {
            day == today -> formats.time
            day == today.minusDays(1) -> return "Yesterday"
            !day.isBefore(today.minusDays(6)) -> formats.weekday
            day.year == today.year -> formats.date
            else -> formats.dateWithYear
        }
        return date.format(DateTimeFormatter.ofPattern(pattern, formats.locale))
    }

    // Connection banner (DESIGN §7.2)

    sealed interface Banner {
        /** No account can reach Instagram (2 failed connects in a row). */
        data object Offline : Banner
        /** One account is signed out, erroring or reconnecting for over 2 minutes. */
        data class Account(val id: String) : Banner
        /** Several accounts are in trouble. */
        data class Accounts(val count: Int) : Banner
    }

    /** At most one banner, most fundamental cause first: without the network nothing else can be known. */
    fun banner(syncFailures: Int, accounts: List<BridgeAPI.Login>, now: Long = System.currentTimeMillis()): Banner? {
        if (syncFailures >= 2) return Banner.Offline
        val trouble = accounts.filter { it.health(now).raisesBanner }
        return when (trouble.size) {
            0 -> null
            1 -> Banner.Account(trouble[0].id)
            else -> Banner.Accounts(trouble.size)
        }
    }
}

/** A person as the engine describes them with a chat (`profiles`): name, picture and @handle. */
@Serializable
data class UserProfile(
    val name: String? = null,
    @SerialName("avatar_url") val avatarURL: String? = null,
    val handle: String? = null,
)
