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
import fi.ferry.messenger.model.Message
import fi.ferry.messenger.model.MessagesPage
import fi.ferry.messenger.model.PendingMessage
import fi.ferry.messenger.model.Room
import fi.ferry.messenger.model.Timeline
import fi.ferry.messenger.model.UserProfile
import fi.ferry.messenger.model.get
import fi.ferry.messenger.model.json
import fi.ferry.messenger.model.long
import fi.ferry.messenger.model.string
import fi.ferry.messenger.ui.Pictures
import kotlin.concurrent.thread
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
    /** The last action that failed, for a message on screen. */
    var actionError by mutableStateOf<String?>(null)
    /** A short confirmation ("Accepted. This chat is now in Studio."). */
    var toast by mutableStateOf<String?>(null)
    /** Chats with an older page on its way (the thread's top spinner). */
    var paginating by mutableStateOf(setOf<String>()); private set
    /** A chat New Message just opened: its thread focuses the composer once (DESIGN N1). */
    var focusComposer by mutableStateOf<String?>(null)

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
    /** Calls waiting for the engine's updates up to a `seq` (New Message's chat). */
    private val seqWaiters = mutableListOf<Pair<Long, () -> Unit>>()
    /**
     * Archived chats, this phone only (Instagram isn't told): room ID → when (ms). Each is the room's archive tag
     * (`Inbox.folder`), so a new message still brings the chat back.
     */
    private var archived = mapOf<String, Long>()
    /** Our photos, GIFs, videos and voice notes on their way or failed, by transaction ID: Retry sends the same file. */
    private val outgoing = mutableMapOf<String, Attachment>()
    private var started = false

    /** Once per process: every stored account connects, an Instagram action each (index, mailbox, folders, socket). */
    fun start(context: Context, demo: Boolean, requestRoom: String? = null) {
        if (started) return
        started = true
        this.demo = demo
        MediaFiles.init(context)
        Outgoing.sweep(context)
        if (demo) return loadDemo()
        requestRoom?.let { IGCore.setDebug(false, it.encodeToByteArray()) }
        prefs = context.getSharedPreferences("ferry", Context.MODE_PRIVATE)
        sessions = Sessions(context)
        identities = read(IDENTITIES) ?: emptyMap()
        requestsSeen = read(REQUESTS_SEEN) ?: emptyMap()
        viewers += read<Map<String, IG.Viewer>>(VIEWERS).orEmpty()
        archived = read(ARCHIVED) ?: emptyMap()
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

    // Thread (write side). Instagram's echo through the socket confirms each one.

    /**
     * A text, with a local echo at once (its transaction ID is the offline threading ID Instagram gets, so the echo
     * replaces it). In a request the engine accepts first; the chat moves to the main list here at once.
     */
    fun send(text: String, replyTo: String?, room: Room) {
        if (Inbox.isRequest(room)) {
            room.requestAction = Room.RequestAction.ACCEPT
            toast = "Accepted. This chat is now in ${accountName(room)}."
        }
        val content = buildJsonObject {
            put("msgtype", "m.text")
            put("body", text)
            if (replyTo != null) putJsonObject("m.relates_to") { putJsonObject("m.in_reply_to") { put("event_id", replyTo) } }
        }
        val pending = PendingMessage(newOTID(), content, now(), PendingMessage.State.Sending)
        room.addPending(pending)
        refresh()
        deliver(pending, room)
    }

    private fun deliver(pending: PendingMessage, room: Room) {
        if (demo || room.id !in rooms) return update(room, pending.txnID, PendingMessage.State.Failed("This is the demo: nothing is sent."))
        val text = Timeline.body(pending.content).encodeToByteArray()
        val replyTo = Timeline.replyTarget(pending.content).orEmpty().encodeToByteArray()
        thread {
            val answer = IGCore.send(room.id.encodeToByteArray(), text, replyTo, pending.txnID.encodeToByteArray())?.decodeToString()
            val failure = failure(answer)
            val id = answer?.let { runCatching { json.decodeFromString<IG.Result>(it).id }.getOrNull() }
            main.post {
                update(room, pending.txnID, if (failure != null) PendingMessage.State.Failed(failure) else PendingMessage.State.Sent(id ?: pending.txnID))
            }
        }
    }

    private fun update(room: Room, txnID: String, state: PendingMessage.State) {
        room.updatePending(txnID, state)
        refresh()
    }

    /** "Not sent. Tap to retry": the same offline threading ID again, so an echo still on its way clears it. */
    fun retry(message: Message, room: Room) {
        val txnID = message.txnID ?: return
        if (message.status !is Message.Status.Failed || message.eventID != null) return
        val pending = room.pending.firstOrNull { it.txnID == txnID } ?: return
        update(room, txnID, PendingMessage.State.Sending)
        if (txnID in outgoing) deliverMedia(pending, room) else deliver(pending, room)
    }

    /** Drops a failed local echo that never reached Instagram. */
    fun discard(message: Message, room: Room) {
        if (message.eventID == null) message.txnID?.let {
            room.removePending(it)
            outgoing.remove(it)?.file?.delete()
            refresh()
        }
    }

    /** Instagram keeps one reaction per person: the same emoji again removes ours, another replaces it. Never in a request. */
    fun react(key: String, message: Message, room: Room) {
        val target = message.eventID ?: return
        // A removal names the key Instagram has ("❤" for the palette's "❤️"): with ours, Instagram kept the heart (A5 check).
        val mine = message.reactions.firstOrNull { Message.Reaction.same(it.key, key) && ME in it.senders }
        val emoji = (mine?.key ?: key).encodeToByteArray()
        perform(room) { IGCore.react(room.id.encodeToByteArray(), target.encodeToByteArray(), emoji, mine != null) }
    }

    /** Our own text, within 15 minutes and 5 edits (the engine refuses otherwise; `Timeline.canEdit` hides the item). */
    fun edit(message: Message, text: String, room: Room) {
        val target = message.eventID ?: return
        perform(room) { IGCore.edit(room.id.encodeToByteArray(), target.encodeToByteArray(), text.encodeToByteArray()) }
    }

    /** Unsend, for everyone (Instagram has no "delete for me"): our own messages. */
    fun unsend(message: Message, room: Room) {
        val target = message.eventID ?: return
        perform(room) { IGCore.unsend(room.id.encodeToByteArray(), target.encodeToByteArray()) }
    }

    /** The composer's typing state as it changes: the engine tells Instagram at most once per 20 s, never in a request. */
    fun setTyping(on: Boolean, room: Room) {
        if (demo || room.id !in rooms) return
        thread { IGCore.typing(room.id.encodeToByteArray(), on) }
    }

    private fun perform(room: Room, work: () -> ByteArray?) {
        if (demo || room.id !in rooms) {
            actionError = "This is the demo: nothing is sent."
            return
        }
        thread {
            val error = failure(work()?.decodeToString())
            if (error != null) main.post { actionError = error }
        }
    }

    /** The engine's failure (`{"error"}`), or null for an answer. */
    private fun failure(answer: String?): String? =
        if (answer == null) "The engine returned nothing." else runCatching { json.decodeFromString<Failure>(answer).error }.getOrNull()

    private fun newOTID(): String = if (demo) "demo-${now()}" else IGCore.newOTID()?.decodeToString().orEmpty()

    // Requests (DESIGN I2/T2)

    /** The chat moves to the main list at once; Instagram accepts, then the engine reads it again. A failure puts it back. */
    fun accept(room: Room) {
        if (!Inbox.isRequest(room)) return
        room.requestAction = Room.RequestAction.ACCEPT
        toast = "Accepted. This chat is now in ${accountName(room)}."
        refresh()
        requestCall(room, IGCore::accept) { room.requestAction = null; toast = null }
    }

    /** Deleted on Instagram for this account too: the chat hides at once and goes with the engine's `RoomRemoved`. */
    fun deleteRequest(room: Room) {
        if (!Inbox.isRequest(room)) return
        room.requestAction = Room.RequestAction.DELETE
        refresh()
        requestCall(room, IGCore::deleteThread) { room.requestAction = null }
    }

    // ponytail: the demo keeps the optimistic Accept or Delete (nothing to ask), so its screens can be checked.
    private fun requestCall(room: Room, call: (ByteArray) -> ByteArray?, undo: () -> Unit) {
        if (demo || room.id !in rooms) return
        thread {
            val error = failure(call(room.id.encodeToByteArray())?.decodeToString())
            if (error != null) main.post { undo(); actionError = error; refresh() }
        }
    }

    // Archive (DESIGN §4.5): this phone only, Instagram isn't told

    fun setArchived(archive: Boolean, room: Room) {
        val tag = Inbox.archiveTag(room, now())
        room.tags = if (archive) room.tags + (Inbox.ARCHIVED_TAG to tag) else room.tags - Inbox.ARCHIVED_TAG
        archived = if (archive) archived + (room.id to (tag[Inbox.ARCHIVED_AT_KEY].long ?: now())) else archived - room.id
        write(ARCHIVED, archived)
        if (archive) toast = "Archived. New messages bring it back."
        refresh()
    }

    private fun archiveTag(at: Long) = buildJsonObject { put(Inbox.ARCHIVED_AT_KEY, at) }

    // New message (DESIGN N1)

    /** Instagram's people search as `account`: an Instagram request each, so the screen waits for a pause. Blocking. */
    fun searchUsers(query: String, account: String): List<BridgeAPI.Contact>? {
        if (demo) return Demo.contacts(query)
        val answer = IGCore.search(account.encodeToByteArray(), query.encodeToByteArray())?.decodeToString() ?: return null
        if (failure(answer) != null) return null
        return runCatching { json.decodeFromString<List<BridgeAPI.Contact>>(answer) }.getOrNull()
    }

    /**
     * The DM with `contact` on `account`: ours, else the one Instagram has (read once), else a draft until the first
     * message makes it (the engine decides). `done` gets the room once its update is in, or why not.
     */
    fun openDM(contact: BridgeAPI.Contact, account: String, done: (room: String?, error: String?) -> Unit) {
        if (demo) {
            val room = Inbox.existingDM(contact.id, account, rooms.values)?.id
            return done(room, if (room == null) "This is the demo: only its own chats open." else null)
        }
        val payload = json.encodeToString(contact).encodeToByteArray()
        thread {
            val answer = IGCore.openDM(account.encodeToByteArray(), payload)?.decodeToString()
            val error = failure(answer)
            val found = answer?.let { runCatching { json.decodeFromString<IG.Result>(it) }.getOrNull() }
            main.post {
                val room = found?.room
                if (error != null || room == null) done(null, error ?: "The engine found no chat.") else afterSeq(found.seq ?: 0) { done(room, null) }
            }
        }
    }

    // Media (A6)

    /**
     * A photo, GIF or video `Outgoing` prepared, or a voice note: a local echo at once (`local:<id>`, its preview
     * seeded), then Instagram's upload. Never in a request (the composer has no attachments there).
     */
    fun sendMedia(attachment: Attachment, replyTo: String?, room: Room) {
        val voice = attachment.kind == Attachment.Kind.VOICE
        val txnID = newOTID()
        val local = LOCAL + txnID
        val content = buildJsonObject {
            put("msgtype", if (voice) "m.audio" else if (attachment.kind == Attachment.Kind.VIDEO) "m.video" else "m.image")
            put("body", "")
            put("url", local)
            putJsonObject("info") {
                put("mimetype", attachment.mimetype)
                if (attachment.width > 0) { put("w", attachment.width); put("h", attachment.height) }
                attachment.duration?.let { put("duration", it) }
                if (attachment.kind == Attachment.Kind.GIF) put("fi.mau.gif", true)
                if (attachment.kind == Attachment.Kind.VIDEO) put("thumbnail_url", local)
            }
            if (voice) putJsonObject("org.matrix.msc3245.voice") {}
            if (replyTo != null) putJsonObject("m.relates_to") { putJsonObject("m.in_reply_to") { put("event_id", replyTo) } }
        }
        attachment.preview?.let { Pictures.seed(local, it) }
        outgoing[txnID] = attachment
        val pending = PendingMessage(txnID, content, now(), PendingMessage.State.Sending)
        room.addPending(pending)
        refresh()
        deliverMedia(pending, room)
    }

    private fun deliverMedia(pending: PendingMessage, room: Room) {
        val attachment = outgoing[pending.txnID] ?: return update(room, pending.txnID, PendingMessage.State.Failed("The file is gone. Send it again."))
        if (demo || room.id !in rooms) return update(room, pending.txnID, PendingMessage.State.Failed("This is the demo: nothing is sent."))
        val voice = pending.content["msgtype"].string == "m.audio"
        val replyTo = Timeline.replyTarget(pending.content).orEmpty()
        thread {
            val answer = IGCore.sendMedia(room.id.encodeToByteArray(), attachment.file.path.encodeToByteArray(), attachment.mimetype.encodeToByteArray(),
                voice, replyTo.encodeToByteArray(), pending.txnID.encodeToByteArray())?.decodeToString()
            val failure = failure(answer)
            main.post { update(room, pending.txnID, if (failure != null) PendingMessage.State.Failed(failure) else PendingMessage.State.Sent(pending.txnID)) }
        }
    }

    /** A local echo's file (`local:<id>`): the viewer opens it before Instagram has it. */
    fun localFile(url: String): java.io.File? = outgoing[url.removePrefix(LOCAL)]?.file?.takeIf { url.startsWith(LOCAL) }

    /** Our upload's echo: its file goes into the media cache under Instagram's link, so it isn't fetched back. */
    private fun fileSent(events: List<fi.ferry.messenger.model.Event>) {
        for (event in events) {
            val txnID = event.unsigned?.transactionID ?: continue
            val attachment = outgoing.remove(txnID) ?: continue
            val url = event.content["url"].string ?: continue
            val mimetype = event.content["info"]["mimetype"].string
            thread {
                MediaFiles.adopt(attachment.file, url, mimetype)
                attachment.file.delete()
            }
        }
    }

    /**
     * "Load video": a share's reel, story or post read in full (a read, so requests allow it). Its link and size come as
     * a hidden edit; `done` runs once that's in, or with why not.
     */
    fun resolveMedia(message: Message, room: Room, done: (error: String?) -> Unit) {
        val id = message.eventID ?: return done("This message isn't on Instagram yet.")
        if (demo || room.id !in rooms) return done("This is the demo: nothing is loaded.")
        thread {
            val answer = IGCore.resolveMedia(room.id.encodeToByteArray(), id.encodeToByteArray())?.decodeToString()
            val error = failure(answer)
            val seq = answer?.let { runCatching { json.decodeFromString<IG.Result>(it).seq }.getOrNull() } ?: 0
            main.post { if (error != null) done(error) else afterSeq(seq) { done(null) } }
        }
    }

    /** A media link that stopped working (they last 33 h to 30 days): the thread is read again for fresh ones. */
    fun refreshMedia(room: Room) {
        if (demo || room.id !in rooms) return
        thread { IGCore.readChat(room.id.encodeToByteArray()) }
    }

    /** `go` once the engine's updates up to `seq` are applied. */
    private fun afterSeq(seq: Long, go: () -> Unit) {
        if (seq <= appliedSeq) go() else seqWaiters += seq to go
    }

    fun accountName(room: Room): String = room.bridge?.receiver?.let { identities[it]?.name } ?: "your chats"

    /** The screens read the rooms again (after a change inside one). */
    private fun refresh() {
        roomsState.value = roomsState.value
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
                seqWaiters.toList().filter { it.first <= appliedSeq }.forEach { seqWaiters.remove(it); it.second() }
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
        if (outgoing.isNotEmpty()) update.update?.timeline?.events?.let(::fileSent)
        archived[id]?.let { if (Inbox.ARCHIVED_TAG !in room.tags) room.tags = room.tags + (Inbox.ARCHIVED_TAG to archiveTag(it)) }
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
    private const val ARCHIVED = "archived"
    private const val LOCAL = "local:"
}
