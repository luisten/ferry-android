package fi.ferry.messenger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The fixtures in `src/test/resources/fixtures` (copied from the iOS repo, see its README) and the IDs they contain. */
object Fixture {
    /** The shared tamoveska↔kivostudio DM in the bridge-era sync: tamoveska's chat and kivostudio's (one per account). */
    const val tamoRoom = "!ehE0v9MxOfM_NXqxrNmHqfZ048PGl7MYoaGvwMU_5XA"
    const val kivoRoom = "!o6PqSlIHi7dXejqWKC9ZeSMhEMiPbIxW90UtmMjw8PU"
    const val tamoLogin = "17841400000000301"
    const val kivoLogin = "17841400000000302"
    /** "Us" in the bridge-era fixtures (the engine's is `IGAdapter.ME`). */
    const val me = "@me:igm.home.arpa"
    /** Each account as seen from the other account's chat. */
    const val tamoGhost = "@instagram_17841400000000301:igm.home.arpa"
    const val kivoGhost = "@instagram_17841400000000302:igm.home.arpa"
    const val bot = "@instagrambot:igm.home.arpa"

    fun text(name: String): String =
        checkNotNull(Fixture::class.java.getResource("/fixtures/$name.json")) { "missing fixture $name.json" }.readText()

    inline fun <reified T> decode(name: String): T = json.decodeFromString(text(name))

    fun event(name: String): Event = decode(name)
    fun events(name: String): List<Event> = decode(name)
    fun messages(name: String): MessagesPage = decode(name)

    /** What the engine makes of Instagram's captures (`engine_<name>.json`). */
    inline fun <reified T> engine(name: String): T = decode("engine_$name")

    /** Ferry's bridge-era initial sync: the dummies' DM (one chat per account) and two personal spaces. */
    fun sync(): SyncResponse = decode("sync_initial")
}

/** `/sync` as Ferry's server sent it: only for the bridge-era fixture, the engine never sends one. */
@Serializable
data class SyncResponse(@SerialName("next_batch") val nextBatch: String, val rooms: Rooms? = null) {
    @Serializable
    data class Rooms(val join: Map<String, JoinedRoom>? = null)
}

/** JSON from Kotlin values, for hand-made content (Swift's JSON literals). */
fun j(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k as String to j(v) })
    is List<*> -> JsonArray(value.map(::j))
    else -> error("not JSON: $value")
}

fun obj(vararg pairs: Pair<String, Any?>): JsonElement = j(mapOf(*pairs))

/** A copy with the value at `path` replaced (null removes it), to derive hand-made cases from real fixtures. */
fun JsonElement.setting(path: List<String>, value: Any?): JsonElement {
    val o = this as? JsonObject ?: return this
    val key = path.first()
    val copy = o.toMutableMap()
    val new = if (path.size == 1) value?.let(::j) else (o[key] ?: emptyObject).setting(path.drop(1), value)
    if (new == null) copy.remove(key) else copy[key] = new
    return JsonObject(copy)
}

fun message(id: String, ts: Long, sender: String = "@instagram_1:igm.home.arpa", body: String = "hi", msgtype: String = "m.text",
            extra: Map<String, Any?> = emptyMap()) =
    Event("m.room.message", j(mapOf("msgtype" to msgtype, "body" to body) + extra), id, sender, originServerTS = ts)

fun state(type: String, content: Map<String, Any?>, key: String = "", ts: Long = 1) =
    Event(type, j(content), "\$$type$key", Fixture.bot, key, ts)

fun edit(id: String, of: String, ts: Long, sender: String, body: String) = Event(
    "m.room.message",
    obj("msgtype" to "m.text", "body" to "* $body", "m.new_content" to mapOf("msgtype" to "m.text", "body" to body),
        "m.relates_to" to mapOf("rel_type" to "m.replace", "event_id" to of)),
    id, sender, originServerTS = ts,
)

fun reaction(id: String, to: String, key: String, sender: String, ts: Long = 2) = Event(
    "m.reaction", obj("m.relates_to" to mapOf("rel_type" to "m.annotation", "event_id" to to, "key" to key)), id, sender, originServerTS = ts,
)

fun redaction(id: String, of: String, ts: Long, sender: String = Fixture.me) =
    Event("m.room.redaction", obj("redacts" to of), id, sender, originServerTS = ts)

fun timeline(vararg events: Event, limited: Boolean = false, prevBatch: String? = null) =
    JoinedRoom(timeline = JoinedRoom.Timeline(events.toList(), limited, prevBatch))

fun stateUpdate(vararg events: Event) = JoinedRoom(state = EventList(events.toList()))

/** A chat from the bridge-era sync. */
fun syncRoom(id: String): Room = Room(id).apply { apply(checkNotNull(Fixture.sync().rooms?.join?.get(id))) }
