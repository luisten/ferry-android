package fi.ferry.messenger

import fi.ferry.messenger.model.BridgeAPI
import fi.ferry.messenger.model.Event
import fi.ferry.messenger.model.EventList
import fi.ferry.messenger.model.IG
import fi.ferry.messenger.model.IGAdapter
import fi.ferry.messenger.model.JoinedRoom
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.MessagesPage
import fi.ferry.messenger.model.PendingMessage
import fi.ferry.messenger.model.Room
import fi.ferry.messenger.model.UserProfile
import fi.ferry.messenger.model.emptyObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId

/**
 * Debug builds only (`adb shell am start -n fi.ferry.messenger/.MainActivity --ez demo true`): made-up accounts and chats
 * (the iOS app's FERRY_DEBUG_DEMO, with its requests and group), for checking the screens and taking screenshots.
 * The stored accounts are left alone and the engine isn't even loaded, so nothing reaches Instagram.
 *
 * Besides the iOS app's chats: Ada Lind (several days, links, emoji, a tombstone and a failed message, for 1.7's day
 * chips, times and links), Ravi Shah (a reaction log entry, which moves the chat up without a row), Elena's photo
 * without a URL, and the iOS group's reactions. Switches (`MainActivity`): `--ez reaction true` brings a reaction to
 * Jonas Berg 10 s in, `--ei group_older <n>` opens the group from a five-message window with `n` pages of 20 older
 * messages after the group's own, `--ei group_extra <n>` adds `n` unread messages to the group.
 */
object Demo {
    const val TAMO = "17841400000000301"
    const val KIVO = "17841400000000302"
    private const val ADA = "17841400000000206"
    private const val RAVI = "17841400000000207"
    /** The chat of `--ez reaction true`: Jonas Berg (read), whose first message is ours. */
    private const val REACTION_ROOM = "!debug-demo-3"

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
        IGAdapter.user(ADA) to UserProfile("Ada Lind", handle = "ada.lind"),
        IGAdapter.user(RAVI) to UserProfile("Ravi Shah", handle = "ravi.shah"),
    )

    /** A message from Noor, now: the demo's live update (`--ez incoming true`). */
    fun incoming(now: Long): Pair<String, Event> =
        "!debug-demo-1" to text("\$debug-incoming-$now", IGAdapter.user(chats[0].fbid), now, "Running 5 min late, save me a seat!")

    /**
     * A live reaction to our first message in Jonas Berg's chat, as the engine sends it (`--ez reaction true`): the
     * reaction, and Instagram's log entry for it (`<log id>~log`), with the chat unread. Never a notification (D2).
     */
    fun reaction(now: Long): Triple<String, List<Event>, JoinedRoom.UnreadCounts> {
        val jonas = IGAdapter.user(chats[2].fbid)
        val key = "😮"
        val reaction = Event("m.reaction", buildJsonObject {
            putJsonObject("m.relates_to") { put("rel_type", "m.annotation"); put("event_id", "\$$REACTION_ROOM-0"); put("key", key) }
        }, "\$debug-live-reaction", jonas, originServerTS = now)
        val log = Event("m.room.message", buildJsonObject {
            put("msgtype", "m.notice"); put("body", "Reacted $key to your message"); put(MessageKind.REACTION_LOG_KEY, true)
        }, "\$debug-live-log~log", jonas, originServerTS = now)
        return Triple(REACTION_ROOM, listOf(reaction, log), JoinedRoom.UnreadCounts(1, 0))
    }

    /**
     * The group as Instagram's mailbox has it (`--ei group_older`): its newest five timeline events, a fresh window
     * (so its state stays) with a cursor, and what its top spinner has still to load, oldest first: the rest of the group,
     * after `pages` pages of 20 made-up messages a minute apart, then the group's own earlier ones.
     */
    fun mailboxCopy(group: Room, pages: Int): List<Event> {
        val timeline = group.events.filter { it.stateKey == null }.sortedBy { it.originServerTS ?: 0 }
        val first = timeline.firstOrNull()?.originServerTS ?: 0
        val older = (0 until pages * 20).reversed().map { n ->
            text("\$debug-group-older-$n", listOf(theo, maya, sam)[n % 3], first - (n + 1) * 60_000L, "Older message ${n + 1}")
        }
        group.apply(JoinedRoom(timeline = JoinedRoom.Timeline(timeline.takeLast(5), limited = true, prevBatch = "debug-older")))
        return older + timeline.dropLast(5)
    }

    /** New Message's search: the made-up people whose name or handle has `query` (their DMs open; the group's don't). */
    fun contacts(query: String): List<BridgeAPI.Contact> = profiles.mapNotNull { (user, p) ->
        val q = query.trim().lowercase()
        if (listOfNotNull(p.name, p.handle).none { q in it.lowercase() }) null
        else BridgeAPI.Contact(user.removePrefix("@ig_"), p.name, p.avatarURL, p.handle?.let { listOf("instagram:$it") })
    }

    /** `busy` (`--ez busy true`): Harbor Coffee read up to our reply, then 30 new messages, more than a screen. */
    fun rooms(now: Long, busy: Boolean = false, groupExtra: Int = 0): List<Room> =
        chats.mapIndexed { i, c ->
            val more = if (busy && i == 1) (1..28).map { Line(false, 39L - it, "Detail $it of the shoot plan") } else emptyList()
            dm("!debug-demo-${i + 1}", c.name, c.fbid, c.account, request = false, c.unread + more.size,
                (c.lines + more).map { Triple(it.fromMe, now - it.minutesAgo * 60_000, it.text) },
                readUpTo = if (busy && i == 1) now - 90 * 60_000 else null,
                extras = { start -> if (c.handle == "elena.rossi") listOf(photoWithoutURL(IGAdapter.user(c.fbid), start + 500)) else emptyList() })
        } + days(now) + ravi(now) +
        requests.mapIndexed { i, r -> dm("!debug-request-${i + 1}", r.name, r.fbid, r.account, request = true, r.unread,
            r.texts.mapIndexed { j, t -> Triple(false, now - r.minutesAgo * 60_000 + j * 40_000, t) }) } +
        group(now, groupExtra) + media(now)

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

    private fun dm(
        id: String, name: String, fbid: String, account: String, request: Boolean, unread: Int, lines: List<Triple<Boolean, Long, String>>,
        readUpTo: Long? = null, extras: (start: Long) -> List<Event> = { emptyList() },
    ): Room {
        val ghost = IGAdapter.user(fbid)
        val start = (lines.minOfOrNull { it.second } ?: 0) - 1000
        val events = listOf(
            state("m.bridge", "instagram", bridge(name, fbid, account, request, dm = true), start),
            state("m.room.name", "", buildJsonObject { put("name", name) }, start),
            state("m.room.member", ghost, member(name), start, ghost),
        ) + (lines.mapIndexed { j, (fromMe, ts, body) -> text("\$$id-$j", if (fromMe) IGAdapter.ME else ghost, ts, body) } + extras(start))
            .sortedBy { it.originServerTS }
        val receipt = readUpTo?.let { ts ->
            val upTo = lines.indexOfLast { it.second <= ts }
            Event("m.receipt", buildJsonObject {
                putJsonObject("\$$id-$upTo") { putJsonObject("m.read") { putJsonObject(IGAdapter.ME) { put("ts", ts) } } }
            })
        }
        return room(id, events, unread, receipt)
    }

    /** An `m.image` with no `url`: a "Photo" card that loads nothing, for the long press and the swipe on a card. */
    private fun photoWithoutURL(sender: String, ts: Long) = Event("m.room.message", buildJsonObject {
        put("msgtype", "m.image"); put("body", "print.jpg")
        putJsonObject("info") { put("w", 1080); put("h", 1350); put("mimetype", "image/jpeg") }
    }, "\$debug-demo-photo", sender, originServerTS = ts)

    /**
     * Ada Lind: a chat over more than a year, for 1.7's day chips and bubble times. Built from local midnight (not
     * `now` minus minutes, which moves with the hour): a message 400 days ago (the year shows), 10 days ago ("Mon 3 Nov"),
     * 3 days ago (the weekday), a pair at 23:59 and 00:01 (a run broken by the day), yesterday, and 15 today with
     * links, emoji, a long text, an unsent one of ours and a failed one (no echo, "Not sent").
     */
    private fun days(now: Long): Room {
        val zone = ZoneId.systemDefault()
        val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone)
        fun at(daysAgo: Long, hour: Int, minute: Int) = midnight.minusDays(daysAgo).withHour(hour).withMinute(minute).toInstant().toEpochMilli()
        val today = midnight.toInstant().toEpochMilli() + 60_000
        val long = "I went through the whole schedule again and the load-in moved to the afternoon, so the soundcheck is at 4, " +
            "doors at 7 and the first act at 8; the north door is the one with the ramp, and parking is behind the loading dock"
        val lines = listOf(
            Triple(false, at(400, 12, 5), "That print from last year still hangs in my hall"),
            Triple(true, at(10, 9, 15), "Booked the room for the 14th"),
            Triple(false, at(3, 18, 30), "Can you send the poster file?"),
            Triple(true, at(3, 18, 42), "Sending it tonight"),
            Triple(false, at(2, 23, 59), "Goodnight!"), // 2 days ago, a minute before midnight: its own chip
            Triple(false, at(1, 0, 1), "Morning already, ha"), // yesterday, a minute after: another, and its own run
            Triple(true, at(1, 9, 0), "Morning! Coffee first"),
            Triple(false, at(1, 9, 2), "Always"),
            Triple(false, today, "Morning! Did you see the schedule?"),
            Triple(true, today + 1 * 240_000, "Just now, it looks good"),
            Triple(false, today + 2 * 240_000, "See https://lowtide.band/annex?day=14, www.example.org. Mail sam@example.com or call +1 555 0100"),
            Triple(true, today + 3 * 240_000, "Tickets at lowtide.band/annex."),
            Triple(false, today + 4 * 240_000, "👍"),
            Triple(true, today + 5 * 240_000, "🎉🎉"),
            Triple(false, today + 6 * 240_000, long),
            Triple(true, today + 7 * 240_000, "Which door?"),
            Triple(false, today + 8 * 240_000, "North one, by the ramp"),
            Triple(false, today + 9 * 240_000, "I'll be there from 3"),
            Triple(true, today + 11 * 240_000, "Haha ok"),
            Triple(true, today + 12 * 240_000, "See you at 8"),
            Triple(false, today + 13 * 240_000, "Bringing the speaker"),
            Triple(true, today + 14 * 240_000, "Perfect"),
        )
        // Ours, unsent (a tombstone: `unsigned.redacted_because` and no content), after "Which door?".
        val tombstone = Event("m.room.message", emptyObject, "\$debug-days-unsent", IGAdapter.ME, originServerTS = today + 7 * 240_000 + 60_000,
            unsigned = Event.Unsigned(redactedBecause = buildJsonObject { put("type", "m.room.redaction") }))
        return dm("!debug-demo-days", "Ada Lind", ADA, TAMO, request = false, unread = 0, lines, extras = { listOf(tombstone) }).apply {
            addPending(PendingMessage("debug-failed", buildJsonObject { put("msgtype", "m.text"); put("body", "Running late, save me a seat") },
                now, PendingMessage.State.Failed("This is the demo: nothing is sent.")))
        }
    }

    /**
     * Ravi Shah (kivostudio, unread): our "Your order ships today" with a 😂 from him, and Instagram's log entry for it
     * two minutes ago, as the engine makes it. It sorts first, previews its words and has no row in the thread.
     */
    private fun ravi(now: Long): Room {
        val ghost = IGAdapter.user(RAVI)
        val id = "!debug-demo-ravi"
        val reaction = Event("m.reaction", buildJsonObject {
            putJsonObject("m.relates_to") { put("rel_type", "m.annotation"); put("event_id", "\$$id-0"); put("key", "😂") }
        }, "\$debug-ravi-reaction", ghost, originServerTS = now - 3 * 60_000)
        val log = Event("m.room.message", buildJsonObject {
            put("msgtype", "m.notice"); put("body", "Reacted 😂 to your message"); put(MessageKind.REACTION_LOG_KEY, true)
        }, "\$debug-ravi-log", ghost, originServerTS = now - 2 * 60_000)
        return dm(id, "Ravi Shah", RAVI, KIVO, request = false, unread = 1,
            listOf(Triple(true, now - 30 * 60_000, "Your order ships today")), extras = { listOf(reaction, log) })
    }

    /**
     * One group: four senders, runs, a reply, reactions (three people on one emoji), a time gap and two chat events.
     * Unread, with our receipt on Sam's last message; Maya and Sam have seen the newest. No pictures: initials.
     * `extra` (`--ei group_extra`): that many more unread messages after Theo's last.
     */
    private fun group(now: Long, extra: Int): Room {
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
        val extraLines = listOf("On my way", "Bringing chalk and the long rope, plus snacks for after", "👍",
            "Anyone need a ride from the station? Two seats left in the back, leaving at 8:45 sharp")
        for (i in 0 until extra) {
            events += text("\$debug-group-extra-$i", listOf(maya, sam, theo)[i % 3], now - 11 * 60_000 + i * 1000L, extraLines[i % extraLines.size])
        }
        // Three on Instagram's "❤" (no selector) beside a 😂: the squeezed pill hid the heart behind the faces; our own
        // short message's pill is wider than the bubble (iOS's DebugFixtures, 2026-10-25).
        listOf(Triple(2, "❤", theo), Triple(2, "❤", sam), Triple(2, "❤", IGAdapter.ME), Triple(2, "😂", priya), Triple(4, "❤️", maya),
            Triple(4, "❤️", sam), Triple(4, "❤️", priya), Triple(4, "🔥", theo), Triple(4, "😂", IGAdapter.ME),
            Triple(6, "👍", theo), Triple(6, "👍", maya), Triple(6, "👍", IGAdapter.ME))
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
        return room("!debug-group", events, unread = 1 + extra, receipts = Event("m.receipt", receipts))
    }

    /**
     * One chat of media in the engine's shapes (core/chat): an album, a voice note, a link with its preview, a reel to
     * load, a video, a GIPHY sticker. Their links go nowhere (`example.invalid`), so the cards show their layout and a failed download.
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
            Triple(false, 70, buildJsonObject {
                put("msgtype", "m.text"); put("body", "This place for Sunday? https://harborview.example/brunch")
                putJsonArray("com.beeper.linkpreviews") {
                    addJsonObject {
                        put("matched_url", "https://harborview.example/brunch"); put("og:title", "Harborview: brunch on the water")
                        put("og:image", "$cdn-link.jpg"); put("og:image:width", 1200); put("og:image:height", 630)
                    }
                }
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
