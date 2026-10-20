package fi.ferry.messenger

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fi.ferry.core.IGCore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The app's side of the engine, as far as A0 goes: stored sessions resumed at launch, new logins, and each account's
 * chat count from the engine's updates. What the screen reads changes on the main thread only.
 */
object Engine {
    var usernames by mutableStateOf(mapOf<String, String>()); private set
    var chats by mutableStateOf(mapOf<String, Int>()); private set
    var status by mutableStateOf<String?>(null); private set

    private lateinit var sessions: Sessions
    private val main = Handler(Looper.getMainLooper())
    private val rooms = mutableMapOf<String, MutableSet<String>>() // the events thread's only

    /** Once per process: every launch with stored accounts is an Instagram action per account (index, mailbox, folders). */
    fun start(context: Context) {
        if (::sessions.isInitialized) return
        sessions = Sessions(context)
        thread(name = "igcore-events", isDaemon = true) { events() }
        for (id in sessions.accounts()) {
            val cookies = sessions.read(id) ?: continue
            thread { connected(IGCore.resume(id.encodeToByteArray(), cookies)) }
        }
    }

    /** The cookies the login web view collected (the user just logged in), for the engine and then not kept. */
    fun login(cookies: Map<String, String>) {
        status = "Connecting…"
        val json = JSONObject(cookies).toString().encodeToByteArray()
        thread { connected(IGCore.login(json)) }
    }

    /** Each account's session as it is now (Instagram rotates some cookies), into the encrypted store only. */
    fun save() {
        val ids = usernames.keys.toList()
        thread { ids.forEach(::store) }
    }

    private fun store(id: String) {
        val session = IGCore.session(id.encodeToByteArray()) ?: return
        if (!JSONObject(session.decodeToString()).has("error")) sessions.write(id, session)
    }

    /** IGLogin's or IGResume's answer (or a retry's `Login` event): `{account, viewer, seq}`, else an error. */
    private fun connected(result: ByteArray?) {
        val r = JSONObject(result?.decodeToString() ?: """{"error": "The engine returned nothing."}""")
        if (r.has("error")) {
            main.post { status = r.getString("error") }
            return
        }
        val id = r.getString("account")
        store(id)
        val name = r.getJSONObject("viewer").optString("username")
        main.post { usernames = usernames + (id to name); status = null }
    }

    // ponytail: only the chat count; A1 applies each update to its chat (Room, Timeline, Inbox).
    private fun events() {
        while (true) {
            val batch = JSONArray((IGCore.nextEvent(60_000) ?: continue).decodeToString())
            for (i in 0 until batch.length()) {
                val e = batch.getJSONObject(i)
                val id = e.optString("account")
                when (e.optString("kind")) {
                    "Room" -> rooms.getOrPut(id) { mutableSetOf() } += e.getString("room")
                    "RoomRemoved" -> rooms[id]?.remove(e.getString("room"))
                    "Login" -> connected(e.toString().encodeToByteArray()) // a stored account's retry got through
                    "LoginFailed" -> e.optString("error").let { main.post { status = it } }
                }
            }
            val counts = rooms.mapValues { it.value.size }
            main.post { chats = counts }
        }
    }
}
