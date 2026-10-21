package fi.ferry.messenger

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import fi.ferry.core.IGCore
import fi.ferry.messenger.model.AccountIdentity
import fi.ferry.messenger.model.BridgeAPI
import fi.ferry.messenger.model.IG
import fi.ferry.messenger.model.IGAdapter
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.MessagesPage
import fi.ferry.messenger.model.Room
import fi.ferry.messenger.model.UserProfile
import fi.ferry.messenger.model.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.concurrent.thread

/**
 * App root state (the iOS app's AppModel, as far as A2 goes): the Instagram accounts (each one's session in
 * [Sessions], a client per account in the engine), their identities, and the chats as rooms, kept current by the
 * engine's updates, which are the only writer of what Instagram says. Everything here changes on the main thread;
 * reading [rooms] subscribes a screen to every change, inside a room too.
 */
object AppModel {
    /** Every stored account, in the shape `Health`, the stack and the banners read. */
    var accounts by mutableStateOf(listOf<BridgeAPI.Login>()); private set
    /** The stored accounts' IDs: a room whose account went hides at once. */
    var loginIDs by mutableStateOf<Set<String>>(emptySet()); private set
    /** Each account's name, colour, mark and order. */
    var identities by mutableStateOf(mapOf<String, AccountIdentity>()); private set
    /** When Requests was last opened, per scope (`Inbox.Scope.key`, ms): what's "new". Local only. */
    var requestsSeen by mutableStateOf(mapOf<String, Long>()); private set
    /** The people in our chats by user ID: names, pictures and @handles, from the engine. */
    var profiles by mutableStateOf(mapOf<String, UserProfile>()); private set
    /** Every account's first page has arrived (or its connection failed): the inbox can tell loading from empty. */
    var initialSyncDone by mutableStateOf(false); private set
    /** A new login is with the engine. */
    var connecting by mutableStateOf(false); private set
    /** Why the last new login failed, in Instagram's or the engine's words. */
    var loginError by mutableStateOf<String?>(null); private set
    /** The inbox's scope: "" for All, else an account ID; kept across launches (DESIGN §4.1). */
    var scopeKey by mutableStateOf(""); private set
    /** Made-up data only (`Demo`): nothing connects. */
    var demo = false; private set
    /** Chats with an older page on its way (the thread's top spinner). */
    var paginating by mutableStateOf(setOf<String>()); private set

    /** The user every screen treats as "us", in every account's chats. */
    const val ME = IGAdapter.ME

    private val roomsState = mutableStateOf(mutableMapOf<String, Room>(), neverEqualPolicy())
    /** The chats by room ID. A change inside a room notifies too (the map is re-sent after each batch of updates). */
    val rooms: Map<String, Room> get() = roomsState.value

    /** Accounts in the user's order (scope bar, stack). */
    val orderedAccounts: List<BridgeAPI.Login>
        get() = accounts.withIndex().sortedWith(compareBy({ identities[it.value.id]?.order ?: Int.MAX_VALUE }, { it.index })).map { it.value }

    val scope: Inbox.Scope get() = if (scopeKey.isEmpty() || scopeKey !in loginIDs) Inbox.Scope.All else Inbox.Scope.Account(scopeKey)

    private data class Status(val state: String, val message: String?, val since: Long)

    @Serializable
    private data class Failure(val error: String? = null, val code: String? = null, val reason: String? = null)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sessions: Sessions
    private lateinit var prefs: SharedPreferences
    private val statuses = mutableMapOf<String, Status>()
    private val viewers = mutableMapOf<String, IG.Viewer>()
    /** Accounts with a client in the engine, or a login in flight. */
    private val running = mutableSetOf<String>()
    /** Accounts whose first page this launch has arrived, or failed. */
    private val loaded = mutableSetOf<String>()
    /** Accounts waiting for their first page: the `seq` of its last update. */
    private val waiting = mutableMapOf<String, Long>()
    private var appliedSeq = 0L
    private var started = false

    /** Once per process: every stored account connects, an Instagram action each (index, mailbox, folders, socket). */
    fun start(context: Context, demo: Boolean) {
        if (started) return
        started = true
        this.demo = demo
        if (demo) return loadDemo()
        prefs = context.getSharedPreferences("ferry", Context.MODE_PRIVATE)
        sessions = Sessions(context)
        identities = read(IDENTITIES) ?: emptyMap()
        requestsSeen = read(REQUESTS_SEEN) ?: emptyMap()
        viewers += read<Map<String, IG.Viewer>>(VIEWERS).orEmpty()
        scopeKey = prefs.getString(SCOPE, "").orEmpty()
        val ids = sessions.accounts()
        for (id in ids) statuses[id] = Status("CONNECTING", null, now())
        updateAccounts()
        updateInitialSync()
        thread(name = "igcore-events", isDaemon = true) { events() }
        ids.forEach(::connect)
    }

    private fun loadDemo() {
        viewers += Demo.viewers
        for (id in Demo.viewers.keys) statuses[id] = Status("CONNECTED", null, now())
        roomsState.value = Demo.rooms(now()).associateByTo(mutableMapOf()) { it.id }
        profiles = Demo.profiles
        loaded += Demo.viewers.keys
        updateAccounts()
        updateInitialSync()
    }

    /** The cookies the login web view collected (the user just logged in): a new account, or a fresh session. */
    fun login(cookies: Map<String, String>) {
        connecting = true
        loginError = null
        val payload = json.encodeToString(cookies).encodeToByteArray()
        thread {
            val result = IGCore.login(payload)
            main.post {
                connecting = false
                connected(null, result)
            }
        }
    }

    fun selectScope(scope: Inbox.Scope) {
        scopeKey = scope.key
        if (!demo) prefs.edit().putString(SCOPE, scopeKey).apply()
    }

    /** Requests looked at in `scope`: the card and the badge clear for what's listed. */
    fun markRequestsSeen(scope: Inbox.Scope) {
        requestsSeen = requestsSeen + (scope.key to Inbox.requestsSeenMarker(rooms.values, scope, loginIDs, now()))
        if (!demo) write(REQUESTS_SEEN, requestsSeen)
    }

    /** To the background: each session as it is now (Instagram rotates some cookies), and C6's counts to Logcat. */
    fun save() {
        if (demo) return
        val ids = running.toList()
        thread { ids.forEach(::storeSession) }
        // C6: every Instagram request since launch, per account and operation (`adb logcat -s ferry`). Counts and IDs only.
        Log.i("ferry", "requests ${IGCore.counts()?.decodeToString()}")
    }

    // Thread (read side)

    /** A chat opened: the engine reads it in full (reactions, edits, reply targets), at most every 30 s. A read. */
    fun openThread(room: Room) {
        if (demo || room.id !in rooms) return
        thread { IGCore.openChat(room.id.encodeToByteArray()) }
    }

    /**
     * The chat is on screen: "Seen" on their newest message, which the engine sends only when the chat is unread and
     * isn't a request (core/chat rules.go); it reads here at once, as an update.
     */
    fun markRead(room: Room) {
        if (demo || room.id !in rooms) return
        thread { IGCore.markRead(room.id.encodeToByteArray()) }
    }

    /** One older page, prepended (a read). At the chat's start the engine says so with `IGAdapter.START_TOKEN`. */
    fun loadOlder(room: Room) {
        val cursor = room.prevBatch ?: return
        if (room.reachedStart || room.id in paginating) return
        if (cursor == IGAdapter.START_TOKEN || demo) {
            room.prepend(MessagesPage(), cursor)
            roomsState.value = roomsState.value
            return
        }
        paginating = paginating + room.id
        thread {
            val text = IGCore.older(room.id.encodeToByteArray())?.decodeToString()
            val page = text?.takeIf { runCatching { json.decodeFromString<Failure>(it).error }.getOrNull() == null }
                ?.let { runCatching { json.decodeFromString<MessagesPage>(it) }.getOrNull() }
            main.post {
                paginating = paginating - room.id
                if (page != null) {
                    room.prepend(page, cursor)
                    roomsState.value = roomsState.value
                }
            }
        }
    }

    /** The contact's @handle: one of our own accounts, or Instagram's username from the chat's people. */
    fun contactHandle(room: Room): String? =
        Inbox.contactHandle(room, accounts) ?: Inbox.contactUserID(room, ME)?.let { profiles[it]?.handle }

    private fun connect(id: String) {
        val cookies = sessions.read(id) ?: return failed(id, "BAD_CREDENTIALS", "This phone can't read the saved login. Log in again.")
        if (!running.add(id)) return
        setStatus(id, "CONNECTING")
        thread {
            val result = IGCore.resume(id.encodeToByteArray(), cookies)
            main.post { connected(id, result) }
        }
    }

    /** IGLogin's or IGResume's answer: the account (its first page's chats come as updates up to `seq`), or why not. */
    private fun connected(resumed: String?, result: ByteArray?) {
        val text = result?.decodeToString()
        val failure = text?.let { runCatching { json.decodeFromString<Failure>(it) }.getOrNull() }
        if (text == null || failure?.error != null) {
            val message = failure?.error ?: "The engine returned nothing."
            if (resumed == null) loginError = message
            else {
                running -= resumed
                failed(resumed, failure?.code ?: "UNKNOWN_ERROR", message)
            }
            return
        }
        val login = json.decodeFromString<IG.Login>(text)
        val id = login.account
        running += id
        viewers[id] = login.viewer
        write(VIEWERS, viewers)
        if (statuses[id]?.state != "CONNECTED") setStatus(id, "CONNECTING") // the socket's Connected may already be in
        thread { storeSession(id) }
        val seq = login.seq ?: 0
        if (seq <= appliedSeq) loaded += id else waiting[id] = seq
        updateInitialSync()
    }

    private fun failed(id: String, code: String, message: String?) {
        setStatus(id, code, message)
        loaded += id
        updateInitialSync()
    }

    /** The session, as IGSession has it now, into the encrypted store and nowhere else. Off the main thread. */
    private fun storeSession(id: String) {
        val session = IGCore.session(id.encodeToByteArray()) ?: return
        if (runCatching { json.decodeFromString<Failure>(session.decodeToString()).error }.getOrNull() == null) sessions.write(id, session)
    }

    private fun setStatus(id: String, state: String, message: String? = null) {
        val old = statuses[id]
        if (old?.state == state && old.message == message) return
        // A disconnect keeps its first time, so "Reconnecting…" can tell a blip from a long outage.
        statuses[id] = Status(state, message, if (old?.state == state) old.since else now())
        updateAccounts()
    }

    private fun updateAccounts() {
        val logins = statuses.keys.sorted().map { id ->
            val s = statuses.getValue(id)
            IGAdapter.login(id, viewers[id], s.state, s.message, s.since, if (s.state == "TRANSIENT_DISCONNECT") s.since else null)
        }
        accounts = logins
        loginIDs = logins.map { it.id }.toSet()
        // Defaults only once Instagram has said who the account is: a stored account's name arrives with its login.
        val withDefaults = AccountIdentity.withDefaults(identities, logins.filter { it.id in viewers })
        if (withDefaults != identities) {
            identities = withDefaults
            if (!demo) write(IDENTITIES, withDefaults)
        }
    }

    private fun updateInitialSync() {
        initialSyncDone = statuses.keys.all { it in loaded }
    }

    // The engine's events

    private fun events() {
        while (true) {
            val batch = IGCore.nextEvent(1_000) ?: continue
            val updates = runCatching { json.decodeFromString<List<IG.Update>>(batch.decodeToString()) }.getOrElse {
                Log.e("ferry", "the engine's updates didn't decode: ${it.javaClass.simpleName}") // never their content
                emptyList()
            }
            main.post {
                updates.forEach(::handle)
                waiting.entries.removeAll { (id, seq) -> (seq <= appliedSeq).also { if (it) loaded += id } }
                updateInitialSync()
                roomsState.value = roomsState.value // the screens read the rooms again
            }
        }
    }

    /** One of the engine's events, in its order: an account's connection, or a chat's change. */
    private fun handle(update: IG.Update) {
        update.seq?.let { appliedSeq = maxOf(appliedSeq, it) }
        val id = update.account ?: return
        when (update.kind) {
            "Connected" -> setStatus(id, "CONNECTED")
            "Disconnected" -> setStatus(id, "TRANSIENT_DISCONNECT", update.error)
            "AuthError" -> setStatus(id, update.code ?: "BAD_CREDENTIALS", update.error)
            "Login" -> update.viewer?.let { // a stored account's retry got through
                running += id
                viewers[id] = it
                setStatus(id, "CONNECTING")
                thread { storeSession(id) }
            }
            "LoginFailed" -> failed(id, update.code ?: "UNKNOWN_ERROR", update.error)
            "Room" -> apply(update)
            "RoomRemoved" -> update.room?.let { roomsState.value.remove(it) }
        }
    }

    /** A chat's change. A room starts with its first fresh window; an update for one already dropped doesn't bring it back. */
    private fun apply(update: IG.Update) {
        val id = update.room ?: return
        val rooms = roomsState.value
        val room = rooms[id] ?: if (update.update?.timeline?.limited == true) Room(id).also {
            it.ownUserID = ME
            rooms[id] = it
        } else return
        IGAdapter.apply(update, room)
        update.profiles?.let { profiles = profiles + it }
    }

    // Stored on this device (SharedPreferences, never backed up): identities, Requests markers, viewers, the scope.

    private inline fun <reified T> read(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> write(key: String, value: T) {
        if (!demo) prefs.edit().putString(key, json.encodeToString(value)).apply()
    }

    private fun now() = System.currentTimeMillis()

    private const val IDENTITIES = "identities"
    private const val REQUESTS_SEEN = "requests.seen"
    private const val VIEWERS = "viewers"
    private const val SCOPE = "inbox.scope"
}
