package fi.ferry.messenger

import fi.ferry.messenger.model.BridgeAPI
import fi.ferry.messenger.model.Event
import fi.ferry.messenger.model.EventList
import fi.ferry.messenger.model.IG
import fi.ferry.messenger.model.IGAdapter
import fi.ferry.messenger.model.JoinedRoom
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.MessagesPage
import fi.ferry.messenger.model.Room
import fi.ferry.messenger.model.UserProfile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Debug builds only (`adb shell am start -n fi.ferry.messenger/.MainActivity --ez demo true`): made-up accounts and chats
 * (the iOS app's FERRY_DEBUG_DEMO, with its requests and group), for checking the screens and taking screenshots.
 * The stored accounts are left alone and the engine isn't even loaded, so nothing reaches Instagram.
 */
object Demo {
    const val TAMO = "17841400000000301"
    const val KIVO = "17841400000000302"

    val viewers = mapOf(
        TAMO to IG.Viewer(fbid = TAMO, username = "tamoveska", fullName = "Tamo Veska", isProfessionalAccount = false),
        KIVO to IG.Viewer(fbid = KIVO, username = "kivostudio", fullName = "Kivo Studio", isProfessionalAccount = true),
    )

    private class Chat(val name: String, val handle: String, val fbid: String, val account: String, val unread: Int, val lines: List<Line>)
    private class Line(val fromMe: Boolean, val minutesAgo: Long, val text: String)

    private val chats = listOf(
        Chat("Noor Haddad", "noor.haddad", "17841400000000201", TAMO, 1, listOf(
            Line(false, 26, "Are we still on for Sunday?"), Line(true, 25, "Yes! Brunch at 11?"), Line(false, 4, "Perfect, I'll book a table"))),
        Chat("Harbor Coffee", "harborcoffee", "17841400000000202", KIVO, 2, listOf(
            Line(false, 95, "Hi! Could we book the studio for a product shoot?"), Line(true, 90, "Of course. Which day works for you?"),
            Line(false, 41, "Next Thursday, around 9?"), Line(false, 40, "We'd bring our own props"))),
        Chat("Jonas Berg", "jonas.berg", "17841400000000203", TAMO, 0, listOf(
            Line(true, 300, "Sent you the photos from the hike"), Line(false, 280, "These are amazing, thank you!"))),
        Chat("Elena Rossi", "elena.rossi", "17841400000000204", KIVO, 0, listOf(
            Line(false, 1500, "Love the new prints. Do you ship to Italy?"), Line(true, 1440, "We do! Shipping takes 5–7 days."))),
    )

    private class Request(val name: String, val fbid: String, val account: String, val minutesAgo: Long, val unread: Int, val texts: List<String>)

    private val requests = listOf(
        Request("Kestrel Bikes", "17841400000000001", TAMO, 120, 2, listOf("Hi! Loved your last shoot.", "Would you be up for a product day in November?")),
        Request("Lena Ortiz", "17841400000000002", KIVO, 300, 1, listOf("Is the studio free for a portrait session the week of the 20th?")),
        Request("crypto.wins.daily", "17841400000000003", TAMO, 1440, 0, listOf("Turn \$200 into \$5,000 this week. DM me to learn the method")),
    )

    private val theo = IGAdapter.user("17841400000000101")
    private val maya = IGAdapter.user("17841400000000102")
    private val sam = IGAdapter.user("17841400000000103")
    private val priya = IGAdapter.user("17841400000000104")

    val profiles: Map<String, UserProfile> = chats.associate { IGAdapter.user(it.fbid) to UserProfile(it.name, handle = it.handle) } + mapOf(
        theo to UserProfile("Theo Park", handle = "theo.climbs"),
        maya to UserProfile("Maya Chen", handle = "maya.chen"),
        sam to UserProfile("Sam Lee"),
        priya to UserProfile("Priya Nair", handle = "priya.n"),
    )

    /** New Message's search: the made-up people whose name or handle has `query` (their DMs open; the group's don't). */
    fun contacts(query: String): List<BridgeAPI.Contact> = profiles.mapNotNull { (user, p) ->
        val q = query.trim().lowercase()
        if (listOfNotNull(p.name, p.handle).none { q in it.lowercase() }) null
        else BridgeAPI.Contact(user.removePrefix("@ig_"), p.name, p.avatarURL, p.handle?.let { listOf("instagram:$it") })
    }

    fun rooms(now: Long): List<Room> =
        chats.mapIndexed { i, c -> dm("!debug-demo-${i + 1}", c.name, c.fbid, c.account, request = false, c.unread,
            c.lines.map { Triple(it.fromMe, now - it.minutesAgo * 60_000, it.text) }) } +
        requests.mapIndexed { i, r -> dm("!debug-request-${i + 1}", r.name, r.fbid, r.account, request = true, r.unread,
            r.texts.mapIndexed { j, t -> Triple(false, now - r.minutesAgo * 60_000 + j * 40_000, t) }) } +
        group(now) + media(now)

    /** The engine's `m.bridge` (core/chat): whose chat, with whom, a DM or (no room type) a group. */
    private fun bridge(name: String, channel: String, account: String, request: Boolean, dm: Boolean): JsonElement = buildJsonObject {
        putJsonObject("channel") {
            put("id", channel)
            put("displayname", name)
            put("fi.mau.receiver", account)
            if (request) put("com.beeper.message_request", true)
        }
        if (dm) put("com.beeper.room_type", "dm")
        putJsonObject("protocol") { put("id", "instagramgo"); put("displayname", "Instagram") }
    }

    private fun state(type: String, key: String, content: JsonElement, ts: Long, sender: String = IGAdapter.ME) =
        Event(type, content, "\$debug-$type-$key-$ts", sender, key, ts)

    private fun text(id: String, sender: String, ts: Long, body: String, extra: Map<String, JsonElement> = emptyMap()) =
        Event("m.room.message", buildJsonObject { put("msgtype", "m.text"); put("body", body); extra.forEach { (k, v) -> put(k, v) } }, id, sender, originServerTS = ts)

    private fun member(name: String) = buildJsonObject { put("membership", "join"); put("displayname", name) }

    private fun dm(id: String, name: String, fbid: String, account: String, request: Boolean, unread: Int, lines: List<Triple<Boolean, Long, String>>): Room {
        val ghost = IGAdapter.user(fbid)
        val start = (lines.minOfOrNull { it.second } ?: 0) - 1000
        val events = listOf(
            state("m.bridge", "instagram", bridge(name, fbid, account, request, dm = true), start),
            state("m.room.name", "", buildJsonObject { put("name", name) }, start),
            state("m.room.member", ghost, member(name), start, ghost),
        ) + lines.mapIndexed { j, (fromMe, ts, body) -> text("\$$id-$j", if (fromMe) IGAdapter.ME else ghost, ts, body) }
        return room(id, events, unread)
    }

    /**
     * One group: four senders, runs, a reply, reactions (three people on one emoji), a time gap and two chat events.
     * Unread, with our receipt on Sam's last message; Maya and Sam have seen the newest. No pictures: initials.
     */
    private fun group(now: Long): Room {
        val start = now - 60 * 60_000
        val events = mutableListOf(
            state("m.bridge", "instagram", bridge("Saturday Climbs", "340282366841710300949128100000000001", TAMO, request = false, dm = false), start),
            state("m.room.name", "", buildJsonObject { put("name", "Saturday Climbs") }, start),
            state("m.room.member", IGAdapter.ME, member("Tamo Veska"), start),
        )
        for (id in listOf(theo, maya, sam, priya)) events += state("m.room.member", id, member(profiles[id]?.name.orEmpty()), start, id)
        val script = listOf(
            Triple(theo, 52.0, "Who's in for Saturday?"), Triple(theo, 51.5, "Thinking the north wall at 9"),
            Triple(maya, 51.0, "In! I'll bring the rope"), Triple(priya, 50.0, "Can't this week, have fun"),
            Triple(IGAdapter.ME, 49.0, "Count me in"), Triple(sam, 48.0, "Is 9 too early?"),
            Triple(sam, 47.5, "I can drive, 3 seats"), Triple(theo, 12.0, "9:30 then. See you all there"),
        )
        script.forEachIndexed { i, (sender, minutesAgo, body) ->
            val reply = if (i == 5) mapOf("m.relates_to" to buildJsonObject { putJsonObject("m.in_reply_to") { put("event_id", "\$debug-group-msg-1") } }) else emptyMap()
            events += text("\$debug-group-msg-$i", sender, now - (minutesAgo * 60_000).toLong(), body, reply)
        }
        events.addAll(events.size - 1, listOf( // before Theo's last message
            Event("m.room.message", buildJsonObject { put("msgtype", "m.notice"); put("body", "Priya left the group"); put(MessageKind.EVENT_KEY, true) },
                "\$debug-group-left", priya, originServerTS = now - 30 * 60_000),
            Event("m.room.message", buildJsonObject { put("msgtype", "m.notice"); put("body", "Sent a poll"); put(MessageKind.UNSUPPORTED_KEY, "a poll") },
                "\$debug-group-poll", sam, originServerTS = now - 20 * 60_000),
        ))
        listOf(Triple(4, "❤️", maya), Triple(4, "🔥", theo), Triple(6, "👍", theo), Triple(6, "👍", maya), Triple(6, "👍", IGAdapter.ME))
            .forEachIndexed { i, (target, key, sender) ->
                events += Event("m.reaction", buildJsonObject {
                    putJsonObject("m.relates_to") { put("rel_type", "m.annotation"); put("event_id", "\$debug-group-msg-$target"); put("key", key) }
                }, "\$debug-group-reaction-$i", sender, originServerTS = now - 47 * 60_000)
            }
        events += state("m.room.member", priya, buildJsonObject { put("membership", "leave") }, now - 30 * 60_000, priya)
        fun read(minutesAgo: Double) = buildJsonObject { put("ts", JsonPrimitive((now - minutesAgo * 60_000).toLong())) }
        val receipts = buildJsonObject {
            putJsonObject("\$debug-group-msg-6") { putJsonObject("m.read") { put(IGAdapter.ME, read(47.0)) } }
            putJsonObject("\$debug-group-msg-7") { putJsonObject("m.read") { put(maya, read(10.0)); put(sam, read(5.0)) } }
        }
        return room("!debug-group", events, unread = 1, receipts = Event("m.receipt", receipts))
    }

    /**
     * One chat of media in the engine's shapes (core/chat): an album, a voice note, a reel to load, a video, a GIPHY
     * sticker. Their links go nowhere (`example.invalid`), so the cards show their layout and a failed download.
     */
    private fun media(now: Long): Room {
        val mira = "17841400000000205"
        val ghost = IGAdapter.user(mira)
        val cdn = "https://scontent.example.invalid/v/demo"
        fun photo(n: Int, w: Int, h: Int) = buildJsonObject {
            put("msgtype", "m.image"); put("body", ""); put("url", "$cdn-photo-$n.jpg")
            putJsonObject("info") { put("mimetype", "image/jpeg"); put("w", w); put("h", h) }
        }
        val album = listOf(photo(1, 1080, 1350), photo(2, 1350, 1080), photo(3, 1080, 1080))
        val start = now - 90 * 60_000
        val parts = listOf<Triple<Boolean, Long, Map<String, JsonElement>>>(
            Triple(false, 80, (album[0] as kotlinx.serialization.json.JsonObject) + mapOf("fi.ferry.album" to kotlinx.serialization.json.JsonArray(album))),
            Triple(false, 78, buildJsonObject {
                put("msgtype", "m.audio"); put("body", ""); put("url", "$cdn-voice.m4a")
                putJsonObject("info") { put("mimetype", "audio/mp4"); put("duration", 12_500) }
                putJsonObject("org.matrix.msc3245.voice") {}
            }),
            Triple(false, 60, buildJsonObject {
                put("msgtype", "m.video"); put("body", ""); put("external_url", "https://www.instagram.com/reel/DEMO123/")
                putJsonObject("info") { put("thumbnail_url", "$cdn-reel-preview.jpg") }
                putJsonObject("com.beeper.unresolved_media") { put("target_url", "https://www.instagram.com/reel/DEMO123/"); put("target_id", "1") }
            }),
            Triple(true, 30, buildJsonObject {
                put("msgtype", "m.video"); put("body", ""); put("url", "$cdn-video.mp4")
                putJsonObject("info") { put("mimetype", "video/mp4"); put("w", 1080); put("h", 1920); put("duration", 8_000); put("thumbnail_url", "$cdn-video-preview.jpg") }
            }),
            Triple(false, 6, buildJsonObject {
                put("msgtype", "m.image"); put("body", ""); put("url", "$cdn-sticker.webp"); put("fi.ferry.sticker", true)
                putJsonObject("info") { put("mimetype", "image/webp"); put("fi.mau.gif", true); put("w", 200); put("h", 200); put("thumbnail_url", "$cdn-sticker.jpg") }
            }),
        )
        val events = listOf(
            state("m.bridge", "instagram", bridge("Mira Holt", mira, TAMO, request = false, dm = true), start),
            state("m.room.name", "", buildJsonObject { put("name", "Mira Holt") }, start),
            state("m.room.member", ghost, member("Mira Holt"), start, ghost),
        ) + parts.mapIndexed { j, (fromMe, minutesAgo, content) ->
            Event("m.room.message", kotlinx.serialization.json.JsonObject(content), "\$debug-media-$j", if (fromMe) IGAdapter.ME else ghost,
                originServerTS = now - minutesAgo * 60_000)
        }
        return room("!debug-demo-media", events, unread = 0)
    }

    private fun room(id: String, events: List<Event>, unread: Int, receipts: Event? = null) = Room(id).apply {
        ownUserID = IGAdapter.ME
        apply(JoinedRoom(
            timeline = JoinedRoom.Timeline(events, limited = true, prevBatch = "debug-start"),
            ephemeral = receipts?.let { EventList(listOf(it)) },
            unreadNotifications = JoinedRoom.UnreadCounts(unread, 0),
        ))
        prepend(MessagesPage(), "debug-start") // the start of the chat: nothing older
    }
}
