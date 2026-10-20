package fi.ferry.messenger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// The Matrix-style chats the engine hands over (core/chat, PLAN-android E2): the iOS app's SyncTypes.swift and
// JSON.swift, as far as the engine fills them. Event content stays raw JSON, read with the accessors below, which
// answer null for a missing key or another type, as Swift's `JSON` does.

/** The engine's JSON: fields this app doesn't read yet are skipped. */
val json = Json { ignoreUnknownKeys = true }

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
val JsonElement?.number: Double? get() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
val JsonElement?.array: JsonArray? get() = this as? JsonArray
val JsonElement?.obj: JsonObject? get() = this as? JsonObject

val emptyObject = JsonObject(emptyMap())

/** One chat's change (`Room` updates' `update`); every part optional. */
@Serializable
data class JoinedRoom(
    val state: EventList? = null,
    val timeline: Timeline? = null,
    val ephemeral: EventList? = null,
    @SerialName("unread_notifications") val unreadNotifications: UnreadCounts? = null,
) {
    @Serializable
    data class Timeline(
        val events: List<Event>? = null,
        val limited: Boolean? = null,
        @SerialName("prev_batch") val prevBatch: String? = null,
    )

    @Serializable
    data class UnreadCounts(
        @SerialName("notification_count") val notificationCount: Int? = null,
        @SerialName("highlight_count") val highlightCount: Int? = null,
    )
}

@Serializable
data class EventList(val events: List<Event>? = null)

/** Any event: a chat's state or timeline event, or an ephemeral one (typing, receipts: no ID, no sender). */
@Serializable
data class Event(
    val type: String,
    val content: JsonElement = emptyObject,
    @SerialName("event_id") val eventID: String? = null,
    val sender: String? = null,
    @SerialName("state_key") val stateKey: String? = null,
    @SerialName("origin_server_ts") val originServerTS: Long? = null,
    val unsigned: Unsigned? = null,
) {
    @Serializable
    data class Unsigned(
        @SerialName("transaction_id") val transactionID: String? = null,
        @SerialName("redacted_because") val redactedBecause: JsonElement? = null,
    )

    /** Unsent before it reached us: it comes with `redacted_because` and empty content. */
    val isPreRedacted: Boolean get() = unsigned?.redactedBecause.let { it != null && it !is JsonNull }

    /** The target of an `m.replace` edit, if this is one. */
    val replacesEventID: String?
        get() {
            val relation = content["m.relates_to"]
            return if (relation["rel_type"].string == "m.replace") relation["event_id"].string else null
        }

    /** A message or sticker that isn't an edit: one row in a thread. */
    val isBaseMessage: Boolean get() = (type == "m.room.message" || type == "m.sticker") && replacesEventID == null

    val redactionTarget: String? get() = if (type == "m.room.redaction") content["redacts"].string else null
}

/** An older page (`IGOlder`): `chunk` is newest first; no `end` at the start of the chat. */
@Serializable
data class MessagesPage(val chunk: List<Event> = emptyList(), val end: String? = null)
