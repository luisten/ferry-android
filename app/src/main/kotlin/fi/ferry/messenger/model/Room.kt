package fi.ferry.messenger.model

import kotlinx.serialization.json.JsonElement

/**
 * One chat as the app knows it (the iOS app's Room.swift): merged state, the raw timeline window, unread counts,
 * typing and receipts, all from the engine's updates, which are the only writer of what Instagram says (PLAN-android
 * E3). The app's own parts are local: sends not echoed yet, the optimistic Accept or Delete, archive tags.
 * Not thread-safe: one thread owns it.
 */
class Room(val id: String) {
    private val version = Version.make()
    private val _state = mutableMapOf<String, MutableMap<String, Event>>()
    private val _events = mutableListOf<Event>()
    private val _receipts = mutableMapOf<String, Receipt>()
    private val _pending = mutableListOf<PendingMessage>()
    private val _related = mutableMapOf<String, Event>()

    /** Current state, `type → state_key → event`, from `state` and from state events inside the timeline. */
    val state: Map<String, Map<String, Event>> get() = seen(_state)
    /** Timeline events in order, oldest first, since the last `limited` reset. */
    val events: List<Event> get() = seen(_events)
    /** Where the window starts, for `IGOlder` (`IGAdapter.START_TOKEN` when there's nothing older). */
    var prevBatch: String? = null; get() = seen(field); private set
    var notificationCount = 0; get() = seen(field); private set
    var highlightCount = 0; get() = seen(field); private set
    /** Who's typing right now (each `m.typing` replaces the list). */
    var typing: List<String> = emptyList(); get() = seen(field); private set
    /** Each user's latest `m.read`; ours (`IGAdapter.ME`) is our watermark. */
    val receipts: Map<String, Receipt> get() = seen(_receipts)
    /** Sends not echoed yet (local echo). */
    val pending: List<PendingMessage> get() = seen(_pending)
    /** Reply targets from outside the window, by event ID. */
    val related: Map<String, Event> get() = seen(_related)
    /** The window reached the chat's first message. */
    var reachedStart = false; get() = seen(field); private set

    /** This device's archive (`Inbox.ARCHIVED_TAG` → when) and unread mark (Instagram isn't told of either). */
    var tags: Map<String, JsonElement> = emptyMap()
        get() = seen(field)
        set(value) { field = value; changed() }
    /** Accept or Delete on a request, from the tap until the engine answers (optimistic). */
    var requestAction: RequestAction? = null
        get() = seen(field)
        set(value) { field = value; changed() }
    /** Us in this chat (`IGAdapter.ME`), to tell the DM's contact apart from us among the members. */
    var ownUserID: String? = null

    data class Receipt(val eventID: String, val ts: Long)

    /**
     * A room's change count, for the screens (Android's own: the iOS app's Room is `@Observable`). Every accessor
     * reads it and every change bumps it. Compose skips a composable handed the same Room again (strong skipping
     * compares it by identity), so the app makes it snapshot state (`AppModel`) and a screen that read the room is
     * recomposed when it changes, the inbox's rows too; the model and its tests keep a plain count.
     */
    open class Version {
        open var count = 0

        companion object {
            var make: () -> Version = ::Version
        }
    }

    /** How many changes the room has had: reading it, as every accessor does, observes the room. */
    val changes: Int get() = version.count

    private fun <T> seen(value: T): T = value.also { version.count }

    private fun changed() {
        version.count++
    }

    enum class RequestAction { ACCEPT, DELETE }

    fun apply(joined: JoinedRoom) {
        joined.state?.events.orEmpty().forEach(::setState)
        joined.timeline?.let { timeline ->
            val events = timeline.events.orEmpty()
            if (timeline.limited == true) {
                _events.clear()
                reachedStart = false
            }
            if (timeline.limited == true || prevBatch == null) prevBatch = timeline.prevBatch
            for (event in events) {
                setState(event)
                _events += event
            }
            // Our echo is in: the pending item is done (also keeps it from coming back after a limited reset).
            val echoed = events.mapNotNullTo(HashSet()) { it.unsigned?.transactionID }
            if (echoed.isNotEmpty()) _pending.removeAll { it.txnID in echoed }
        }
        joined.unreadNotifications?.let {
            notificationCount = it.notificationCount ?: 0
            highlightCount = it.highlightCount ?: 0
        }
        for (event in joined.ephemeral?.events.orEmpty()) {
            when (event.type) {
                "m.typing" -> typing = event.content["user_ids"].array.orEmpty().mapNotNull { it.string }
                "m.receipt" -> mergeReceipts(event.content)
            }
        }
        // The engine re-sent the chat without the request flag: the accept is done.
        if (requestAction == RequestAction.ACCEPT && bridge?.isRequest != true) requestAction = null
        changed()
    }

    private fun setState(event: Event) {
        val key = event.stateKey ?: return
        _state.getOrPut(event.type) { mutableMapOf() }[key] = event
    }

    /**
     * Prepends an older page fetched from `token`; dropped if a limited update replaced the window meanwhile (it
     * would leave a gap). Its state events only fill keys we don't know yet, never overwrite current state.
     */
    fun prepend(page: MessagesPage, from: String) {
        if (from != prevBatch) return
        val known = _events.mapNotNullTo(HashSet()) { it.eventID }
        val older = page.chunk.asReversed().filter { it.eventID !in known }
        // Newest first, so a member gets their latest event in the page (a join), not their first (an invite).
        for (event in older.asReversed()) {
            val key = event.stateKey ?: continue
            val byKey = _state.getOrPut(event.type) { mutableMapOf() }
            if (key !in byKey) byKey[key] = event
        }
        _events.addAll(0, older)
        prevBatch = page.end
        reachedStart = page.end == null || page.chunk.isEmpty()
        changed()
    }

    fun addPending(message: PendingMessage) {
        _pending += message
        changed()
    }

    fun updatePending(txnID: String, state: PendingMessage.State) {
        val i = _pending.indexOfFirst { it.txnID == txnID }
        if (i >= 0) _pending[i] = _pending[i].copy(state = state) // else the echo came first
        changed()
    }

    fun removePending(txnID: String) {
        _pending.removeAll { it.txnID == txnID }
        changed()
    }

    fun addRelated(event: Event) {
        event.eventID?.let { _related[it] = event }
        changed()
    }

    private fun mergeReceipts(content: JsonElement) {
        for ((eventID, kinds) in content.obj.orEmpty()) {
            for ((user, info) in kinds["m.read"].obj.orEmpty()) {
                val ts = info["ts"].long ?: 0
                if (ts >= (_receipts[user]?.ts ?: Long.MIN_VALUE)) _receipts[user] = Receipt(eventID, ts)
            }
        }
    }

    // Derived (pure)

    fun stateEvent(type: String, key: String = ""): Event? = state[type]?.get(key)

    /** The engine's `m.bridge`: whose chat it is, with whom, and where it goes. */
    val bridge: BridgeInfo?
        get() = state["m.bridge"]?.entries?.sortedBy { it.key }?.firstNotNullOfOrNull { BridgeInfo.parse(it.value.content) }

    val name: String
        get() = stateEvent("m.room.name")?.content?.get("name").string?.takeIf { it.isNotEmpty() }
            ?: bridge?.displayName?.takeIf { it.isNotEmpty() }
            ?: "Instagram chat"

    val avatarURL: String?
        get() = stateEvent("m.room.avatar")?.content?.get("url").string?.takeIf { it.isNotEmpty() } ?: bridge?.avatarURL

    /** Instagram's unread, or marked unread on this device (`Inbox.MARKED_UNREAD_TAG`). */
    val isUnread: Boolean get() = notificationCount > 0 || highlightCount > 0 || isMarkedUnread
    val isMarkedUnread: Boolean get() = Inbox.MARKED_UNREAD_TAG in tags

    fun memberName(user: String): String? = stateEvent("m.room.member", user)?.content?.get("displayname").string

    /** A user's own name: their member event's, else their profile's; null when neither has one. */
    fun knownName(of: String, profile: UserProfile? = null): String? =
        listOf(memberName(of), profile?.name).firstOrNull { !it.isNullOrEmpty() }

    /** Who a user is, never a raw ID: `knownName`, else in a DM the chat's name (the contact's), else the localpart. */
    fun displayName(of: String, profile: UserProfile? = null): String =
        knownName(of, profile) ?: if (bridge?.isDM == true && of != ownUserID) name else localpart(of)

    /** A member's picture: their member event's, else their profile's. */
    fun avatarURL(of: String, profile: UserProfile? = null): String? =
        listOf(stateEvent("m.room.member", of)?.content?.get("avatar_url").string, profile?.avatarURL).firstOrNull { !it.isNullOrEmpty() }

    /** The newest message in the window that is neither an edit nor unsent. */
    val lastMessage: Event? get() = lastMessage { true }

    fun lastMessage(predicate: (Event) -> Boolean): Event? {
        val events = events
        val redacted = events.mapNotNullTo(HashSet()) { it.redactionTarget }
        return events.lastOrNull { it.isBaseMessage && !it.isPreRedacted && (it.eventID ?: "") !in redacted && predicate(it) }
    }

    /** The inbox's order: the last message's time. State events are stamped when the engine made them. */
    val lastActivity: Long get() = lastMessage?.originServerTS ?: events.lastOrNull()?.originServerTS ?: 0

    /** What a message shows: its latest edit's `m.new_content` (same sender only), else its own content. */
    fun currentContent(of: Event): JsonElement =
        events.lastOrNull { it.replacesEventID == of.eventID && it.sender == of.sender }?.content?.get("m.new_content") ?: of.content

    companion object {
        /** "ig_123" for "@ig_123", "instagram_123" for "@instagram_123:server". */
        fun localpart(user: String): String = user.removePrefix("@").substringBefore(':')
    }
}

/** What a chat's `m.bridge` says (core/chat `bridgeInfo`). */
data class BridgeInfo(
    /** The account (FBID) this chat belongs to. */
    val receiver: String?,
    /** In a DM, the other person's ID. */
    val channelID: String?,
    val displayName: String?,
    val avatarURL: String?,
    val isRequest: Boolean,
    /** A DM; anything else is a group. */
    val isDM: Boolean,
    val folder: InboxFolder,
) {
    /** Instagram's tab on professional accounts. */
    enum class InboxFolder { PRIMARY, GENERAL }

    companion object {
        const val PROTOCOL_ID = "instagramgo"
        const val INBOX_FOLDER_KEY = "fi.mau.instagram.inbox_folder"

        fun parse(content: JsonElement): BridgeInfo? {
            if (content["protocol"]["id"].string != PROTOCOL_ID) return null
            val channel = content["channel"]
            return BridgeInfo(
                receiver = channel["fi.mau.receiver"].string,
                channelID = channel["id"].string,
                displayName = channel["displayname"].string,
                avatarURL = channel["avatar_url"].string,
                isRequest = channel["com.beeper.message_request"].bool == true, // absent means no
                isDM = content["com.beeper.room_type"].string == "dm", // a group has none
                folder = if (channel[INBOX_FOLDER_KEY].string == "GENERAL") InboxFolder.GENERAL else InboxFolder.PRIMARY,
            )
        }
    }
}
