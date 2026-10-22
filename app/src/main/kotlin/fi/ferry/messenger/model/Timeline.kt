package fi.ferry.messenger.model

import kotlinx.serialization.json.JsonElement
import java.net.URI

// The thread (the iOS app's Timeline.swift): what each message shows as, and the pure fold from a chat's raw window
// to its rows. Only what the engine sends: no bridge send statuses, no `xma_fetch_status` failures.

/** What a message shows as (DESIGN §6.3–6.8), from its current content (the latest edit's `m.new_content`). */
sealed interface MessageKind {
    data object Text : MessageKind
    data object Notice : MessageKind
    data object Image : MessageKind
    data object Video : MessageKind
    data object Audio : MessageKind
    data object Voice : MessageKind
    data object File : MessageKind
    data object Sticker : MessageKind
    /** Instagram's GIFs (`animated_media`) and the ones we send, marked `info.fi.mau.gif`. */
    data object Gif : MessageKind
    /** A text with a link preview (`com.beeper.linkpreviews`). */
    data object Link : MessageKind
    /** View-once media: a notice instead. */
    data object ViewOnce : MessageKind
    /** Unsent: a tombstone that keeps its sender and time. */
    data object Unsent : MessageKind
    /** Instagram's own line about the chat ("… named the group …"): `body` as it is. */
    data object ChatEvent : MessageKind
    /** An Instagram share and whether its media is here yet ("Load video"). */
    data class Share(val type: ShareType, val state: ShareState) : MessageKind
    /** Something the app can't show: what it is, "a poll" or "a message Ferry can't show". */
    data class Unsupported(val what: String) : MessageKind

    /** `OTHER`: anything shared that isn't a reel, post or story (a profile, music…). */
    enum class ShareType(val label: String, val phrase: String, val glyph: Glyph) {
        REEL("Reel", "a reel", Glyph.REEL),
        POST("Post", "a post", Glyph.POST),
        STORY("Story", "a story", Glyph.STORY),
        OTHER("Attachment", "an attachment", Glyph.LINK);

        companion object {
            fun fromInstagramURL(link: String): ShareType? {
                val url = parseURL(link) ?: return null
                val host = url.host ?: return null
                if (host != "instagram.com" && !host.endsWith(".instagram.com")) return null
                val path = url.rawPath.orEmpty()
                return when {
                    "/reel/" in path || "/reels/" in path -> REEL
                    "/p/" in path -> POST
                    "/stories/" in path -> STORY
                    else -> null
                }
            }
        }
    }

    enum class ShareState { READY, UNRESOLVED }

    /** A non-text kind's one-line glyph and label (inbox preview, reply quote). */
    data class Media(val glyph: Glyph, val label: String)

    val media: Media?
        get() = when (this) {
            Text, Notice, Link, ChatEvent -> null
            Image -> Media(Glyph.PHOTO, "Photo")
            Video -> Media(Glyph.VIDEO, "Video")
            Audio -> Media(Glyph.WAVEFORM, "Audio")
            Voice -> Media(Glyph.WAVEFORM, "Voice message")
            File -> Media(Glyph.DOC, "File")
            Sticker -> Media(Glyph.STICKER, "Sticker")
            Gif -> Media(Glyph.PHOTO, "GIF")
            is Share -> Media(type.glyph, type.label)
            ViewOnce -> Media(Glyph.VIEW_ONCE, "View-once media")
            Unsent -> Media(Glyph.UNSENT, "Unsent message")
            is Unsupported -> Media(Glyph.UNSUPPORTED, "Unsupported message")
        }

    /** A line across the thread, not a bubble: no sender label, menu or status. */
    val isChatEvent: Boolean get() = this == ChatEvent || this is Unsupported

    companion object {
        const val EVENT_KEY = "fi.ferry.event"
        const val UNSUPPORTED_KEY = "fi.ferry.unsupported"
        /** The text sent with a share: a story reply, or a story reaction's emoji. */
        const val SHARE_TEXT_KEY = "fi.ferry.share_text"
        /** A sticker from Instagram's GIPHY picker: an animated image (`MediaInfo.isAnimated`) shown as a sticker. */
        const val STICKER_KEY = "fi.ferry.sticker"

        fun of(type: String, content: JsonElement): MessageKind {
            if (type == "m.sticker") return Sticker
            // Only shares carry `external_url`.
            val share = content["external_url"].string?.let { ShareType.fromInstagramURL(it) ?: ShareType.OTHER }
            if (content["com.beeper.unresolved_media"] != null) return Share(share ?: ShareType.REEL, ShareState.UNRESOLVED)
            val body = content["body"].string.orEmpty()
            return when (content["msgtype"].string) {
                "m.image" -> if (content[STICKER_KEY] != null) Sticker
                    else share?.let { Share(it, ShareState.READY) } ?: if (content["info"]["fi.mau.gif"].bool == true) Gif else Image
                "m.video" -> share?.let { Share(it, ShareState.READY) } ?: Video
                "m.audio" -> if (content["org.matrix.msc3245.voice"] != null) Voice else Audio
                "m.file" -> File
                "m.notice" -> {
                    val unsupported = content[UNSUPPORTED_KEY].string
                    when {
                        body.startsWith("This ") && body.endsWith(" once. Use the Instagram app to view.") -> ViewOnce
                        content[EVENT_KEY] != null -> ChatEvent
                        unsupported != null -> Unsupported(unsupported)
                        else -> Notice
                    }
                }
                // Unknown msgtypes fall back to their body.
                else -> if (content["com.beeper.linkpreviews"].array?.isNotEmpty() == true) Link else Text
            }
        }
    }
}

/** What a glyph means; the screens pick the Material Symbol (iOS: an SF Symbol). */
enum class Glyph { PHOTO, VIDEO, WAVEFORM, DOC, STICKER, REEL, POST, STORY, LINK, VIEW_ONCE, UNSENT, UNSUPPORTED }

/** A link as a URL, or null if it isn't one. */
internal fun parseURL(link: String): URI? = runCatching { URI(link) }.getOrNull()

/** The fields media cards need from a message's current content. */
class MediaInfo(content: JsonElement) {
    private val info = content["info"]
    val msgtype: String? = content["msgtype"].string
    /** The media itself. */
    val url: String? = content["url"].string
    val mimetype: String? = info["mimetype"].string
    /** Bytes as Instagram reports them. */
    val size: Long? = info["size"].long
    val width: Double? = info["w"].number
    val height: Double? = info["h"].number
    /** Milliseconds. Voice notes carry it; reels don't. */
    val duration: Long? = (info["duration"] ?: content["org.matrix.msc1767.audio"]["duration"]).long
    val thumbnailURL: String? = info["thumbnail_url"].string
    val externalURL: String? = content["external_url"].string?.takeIf { parseURL(it) != null }
    /** Photos and videos sent together, each its own content; empty for one. */
    val album: List<JsonElement> = content[ALBUM_KEY].array.orEmpty()
    /** A GIF, or a sticker from Instagram's GIPHY picker: plays like one (`info.fi.mau.gif`). */
    val isAnimated: Boolean = info["fi.mau.gif"].bool == true

    val isVideo: Boolean get() = msgtype == "m.video"

    /** "3 photos", "2 videos", "3 photos and videos": the pile's badge and "Share All". */
    val albumLabel: String
        get() {
            val videos = album.count { it["msgtype"].string == "m.video" }
            return "${album.size} ${if (videos == 0) "photos" else if (videos == album.size) "videos" else "photos and videos"}"
        }

    /** What a card shows before anything downloads: a video's thumbnail, an image itself. */
    val previewURL: String? get() = if (isVideo) thumbnailURL else url

    /** Height over width, when known. */
    val aspect: Double?
        get() {
            val w = width ?: return null
            val h = height ?: return null
            return if (w > 0 && h > 0) h / w else null
        }

    companion object {
        const val ALBUM_KEY = "fi.ferry.album"
    }
}

/**
 * A share's caption, from `formatted_body` as the engine builds it (the bridge's `wrapXMACaption`), every part
 * optional: `<blockquote>eyebrow</blockquote><strong>title</strong><br>subtitle<p>caption</p><p><a href="url">url</a></p>`.
 * A post's title is "<handle> <caption…>"; a story's author is in its URL; a reel's is unknown.
 */
data class ShareCaption(
    val eyebrow: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val caption: String? = null,
    /** The author's handle when the share's shape gives it away; else `author` falls back to a handle-shaped title. */
    val handle: String? = null,
) {
    val author: String? get() = handle ?: title?.let(::handleShaped)

    companion object {
        /** The caption of a message's current content: its `formatted_body`, plus what its `external_url` says. */
        fun of(content: JsonElement): ShareCaption {
            val caption = fromHTML(content["formatted_body"].string)
            val link = content["external_url"].string ?: return caption
            val url = parseURL(link) ?: return caption
            return when (MessageKind.ShareType.fromInstagramURL(link)) {
                MessageKind.ShareType.POST -> {
                    // The title is "<handle> <caption>"; split it only when the first word is handle-shaped.
                    val title = caption.title
                    val space = title?.indexOf(' ') ?: -1
                    val first = if (caption.caption == null && space >= 0) handleShaped(title!!.substring(0, space)) else null
                    if (first == null) caption else {
                        val rest = title!!.substring(space).trim(' ', '\t')
                        caption.copy(handle = first, caption = rest.ifEmpty { null }, title = null)
                    }
                }
                MessageKind.ShareType.STORY -> {
                    // `/stories/<handle>/<media id>`
                    val parts = url.path.orEmpty().split('/').filter { it.isNotEmpty() }
                    val at = parts.indexOf("stories")
                    if (at >= 0 && at + 1 < parts.size) caption.copy(handle = handleShaped(parts[at + 1])) else caption
                }
                else -> caption
            }
        }

        fun fromHTML(html: String?): ShareCaption {
            if (html.isNullOrEmpty()) return ShareCaption()
            fun text(match: String?): String? =
                match?.replace("<br/>", "\n")?.replace("<br>", "\n")?.let(::unescape)?.ifEmpty { null }
            // Every paragraph but the trailing link is caption text (escaped, its own line breaks as `<br/>`).
            val paragraphs = PARAGRAPH.findAll(html).map { it.groupValues[1] }.filter { !it.startsWith("<a href=") }.toList()
            return ShareCaption(
                eyebrow = text(BLOCKQUOTE.find(html)?.groupValues?.get(1)),
                title = text(STRONG.find(html)?.groupValues?.get(1)),
                subtitle = text(LINE.find(html)?.groupValues?.get(1)),
                caption = text(if (paragraphs.isEmpty()) null else paragraphs.joinToString("<br/>")),
            )
        }

        private val BLOCKQUOTE = Regex("<blockquote>(.*?)</blockquote>")
        private val STRONG = Regex("<strong>(.*?)</strong>")
        private val LINE = Regex("<br>([^<]*)") // the caption's own line breaks are `<br/>`
        private val PARAGRAPH = Regex("<p>(.*?)</p>")
        private val HANDLE = Regex("[A-Za-z0-9._]{1,30}")

        /** Instagram usernames: letters, digits, `.` and `_`, at most 30. */
        private fun handleShaped(text: String): String? = text.takeIf { HANDLE.matches(it) }

        /** Go's `html.EscapeString` escapes exactly these five. */
        private fun unescape(text: String): String = text.replace("&lt;", "<").replace("&gt;", ">").replace("&#34;", "\"")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
    }
}

/** The first `com.beeper.linkpreviews` entry of a text. */
data class LinkPreview(val url: String, val title: String?, val summary: String?, val imageURL: String?, val aspect: Double?) {
    companion object {
        fun of(content: JsonElement): LinkPreview? {
            val preview = content["com.beeper.linkpreviews"].array?.firstOrNull() ?: return null
            val url = (preview["matched_url"] ?: preview["og:url"]).string?.takeIf { parseURL(it) != null } ?: return null
            // Either a number or a string.
            fun number(key: String): Double? = preview[key].number ?: preview[key].string?.toDoubleOrNull()
            val w = number("og:image:width")
            val h = number("og:image:height")
            return LinkPreview(url, preview["og:title"].string, preview["og:description"].string, preview["og:image"].string,
                if (w != null && h != null && w > 0) h / w else null)
        }
    }
}

/** One row of a thread, after edits, reactions, unsends, replies, local echo and receipts are folded in. */
data class Message(
    /** The event ID, or `txn:<id>` for a local echo the engine hasn't confirmed. */
    val id: String,
    val eventID: String? = null,
    val txnID: String? = null,
    val sender: String,
    val ts: Long,
    val kind: MessageKind,
    /** Text to show: the latest edit's body, reply fallback stripped. Empty for tombstones. */
    val body: String,
    /** The current content (the latest edit's `m.new_content`), for media cards. */
    val content: JsonElement,
    val isOwn: Boolean,
    val isEdited: Boolean = false,
    val editCount: Int = 0,
    val reply: Reply? = null,
    val reactions: List<Reaction> = emptyList(),
    /** Only meaningful for our own messages. */
    val status: Status = Status.Sent,
    /** On our last message once someone else's read receipt is at or after it. */
    val isSeen: Boolean = false,
    /** On the newest message: everyone else whose receipt is at or after it, first reader first (groups). */
    val seenBy: List<String> = emptyList(),
) {
    /** What was sent with a shared story (a reply, a reaction's emoji), shown under its card. */
    val shareText: String? get() = content[MessageKind.SHARE_TEXT_KEY].string

    /** Ours, sent from this phone and not back from Instagram yet. Our own from Instagram's app arrives as an event. */
    val isLocalEcho: Boolean get() = id.startsWith("txn:")

    /** Instagram sends no "delivered": Sending, then Sent, then Seen (`isSeen`). */
    sealed interface Status {
        data object Sending : Status
        data object Sent : Status
        data class Failed(val reason: String?) : Status
    }

    data class Reaction(
        val key: String,
        val senders: List<String>,
        /** Our reaction event with this key, so choosing it again removes it. */
        val myEventID: String?,
    ) {
        val count: Int get() = senders.size

        companion object {
            /**
             * One reaction with or without its variation selector: we send "❤️", Instagram stores and echoes "❤"
             * (Android's A4 check, 2026-10-21: choosing ❤️ again reacted again instead of removing it).
             */
            fun same(a: String, b: String): Boolean = a.replace("\uFE0F", "") == b.replace("\uFE0F", "")
        }
    }

    /** Whether `user` reacted with `key`. */
    fun reacted(key: String, user: String): Boolean = reactions.any { Reaction.same(it.key, key) && user in it.senders }

    data class Reply(
        val eventID: String,
        /** null until the target is loaded. */
        val sender: String?,
        val kind: MessageKind?,
        val body: String,
    ) {
        val isLoaded: Boolean get() = sender != null
    }
}

/** A send we've started whose echo hasn't come back yet (local echo). */
/**
 * The long press's reactions (iOS 1.5's RecentReactions): the last six used on this phone, in place, Instagram's six
 * until then; our current one always there.
 */
object RecentReactions {
    val defaults = listOf("❤️", "😂", "😮", "😢", "🔥", "👍")

    fun row(stored: List<String>?): List<String> {
        val row = stored.orEmpty().take(defaults.size).toMutableList()
        for (key in defaults) if (row.size < defaults.size && row.none { Message.Reaction.same(it, key) }) row += key
        return row
    }

    /** Already there: nothing moves under the thumb. Else it goes first and the oldest drops. */
    fun adding(key: String, row: List<String>): List<String> =
        if (row.any { Message.Reaction.same(it, key) }) row else (listOf(key) + row).take(defaults.size)

    /** Our current reaction, in the last place when it isn't one of the six: it's how to remove it. */
    fun shown(row: List<String>, current: String?): List<String> =
        if (current == null || row.any { Message.Reaction.same(it, current) }) row else row.dropLast(1) + current
}

data class PendingMessage(val txnID: String, val content: JsonElement, val ts: Long, val state: State) {
    sealed interface State {
        data object Sending : State
        data class Sent(val id: String) : State
        data class Failed(val reason: String) : State
    }
}

/** The pure fold from a chat's raw events to thread rows. */
object Timeline {
    /** Instagram's limits. */
    const val EDIT_MAX_AGE = 900_000L
    const val EDIT_MAX_COUNT = 5
    const val MAX_TEXT_LENGTH = 1000

    fun reduce(room: Room, me: String): List<Message> =
        reduce(room.events, me, room.pending, room.receipts, room.related)

    /**
     * `events`: the window in timeline order (not timestamp order). `related`: reply targets from outside it.
     */
    fun reduce(
        events: List<Event>,
        me: String,
        pending: List<PendingMessage> = emptyList(),
        receipts: Map<String, Room.Receipt> = emptyMap(),
        related: Map<String, Event> = emptyMap(),
    ): List<Message> {
        val position = HashMap<String, Int>()
        val redacted = HashSet<String>()
        val echoed = HashSet<String>()
        events.forEachIndexed { i, event ->
            event.eventID?.let { position[it] = i }
            event.redactionTarget?.let { redacted += it }
            event.unsigned?.transactionID?.let { echoed += it }
        }
        fun isRedacted(event: Event) = event.isPreRedacted || (event.eventID ?: "") in redacted

        val bases = mutableListOf<Event>()
        val edits = HashMap<String, MutableList<Event>>()
        // Each sender's latest surviving reaction per target (Instagram allows one per person).
        val reactions = HashMap<String, MutableMap<String, Event>>()
        for (event in events) {
            when (event.type) {
                "m.room.message", "m.sticker" -> {
                    val target = event.replacesEventID
                    if (target != null) {
                        if (!isRedacted(event)) edits.getOrPut(target) { mutableListOf() } += event
                    } else if (event.eventID != null) {
                        bases += event
                    }
                }
                "m.reaction" -> {
                    val relation = event.content["m.relates_to"]
                    val target = relation["event_id"].string
                    val sender = event.sender
                    if (!isRedacted(event) && relation["rel_type"].string == "m.annotation" && relation["key"].string != null &&
                        target != null && sender != null
                    ) reactions.getOrPut(target) { mutableMapOf() }[sender] = event
                }
            }
        }

        val messages = bases.mapTo(mutableListOf()) { base ->
            val id = base.eventID.orEmpty()
            val sender = base.sender.orEmpty()
            if (isRedacted(base)) {
                // Edits to it and reactions on it go with it.
                return@mapTo Message(id, id, sender = sender, ts = base.originServerTS ?: 0, kind = MessageKind.Unsent, body = "",
                    content = emptyObject, isOwn = sender == me)
            }
            // Only the original sender can edit; the latest by timeline order wins, edits held until the target loads.
            val applied = edits[id].orEmpty().filter { it.sender == base.sender && it.content["m.new_content"].obj != null }
            val content = applied.lastOrNull()?.content?.get("m.new_content") ?: base.content
            Message(
                id, id, base.unsigned?.transactionID, sender, base.originServerTS ?: 0, MessageKind.of(base.type, content), body(content),
                content, isOwn = sender == me,
                // `dont_render_edited` on the edit's top-level content: "Load video" swapped the card in place.
                isEdited = applied.any { it.content["com.beeper.dont_render_edited"].bool != true },
                // Distinct texts: the same edit twice counts once toward the limit.
                editCount = applied.map { listOf(it.content["m.new_content"]["msgtype"], it.content["m.new_content"]["body"]) }.toSet().size,
                reactions = group(reactions[id]?.values.orEmpty(), position, me),
            )
        }

        for (p in pending) {
            if (p.txnID in echoed) continue
            val state = p.state
            if (state is PendingMessage.State.Sent && state.id in position) continue // echoed without our transaction ID
            messages += Message(
                "txn:${p.txnID}", (state as? PendingMessage.State.Sent)?.id, p.txnID, me, p.ts, MessageKind.of("m.room.message", p.content),
                body(p.content), p.content, isOwn = true,
                status = when (state) {
                    PendingMessage.State.Sending -> Message.Status.Sending
                    is PendingMessage.State.Sent -> Message.Status.Sent
                    is PendingMessage.State.Failed -> Message.Status.Failed(state.reason)
                },
            )
        }

        // Replies last, so a quote shows its target as it is now (edited, or unsent).
        val byID = LinkedHashMap<String, Message>()
        for (m in messages) m.eventID?.let { byID.putIfAbsent(it, m) }
        val originals = HashMap<String, JsonElement>()
        for (b in bases) originals.putIfAbsent(b.eventID.orEmpty(), b.content)
        for (i in messages.indices) {
            val m = messages[i]
            if (m.kind == MessageKind.Unsent) continue
            val target = replyTarget(m.eventID?.let { originals[it] } ?: m.content) ?: continue
            messages[i] = m.copy(reply = quote(target, byID, related))
        }

        // Seen: someone else's m.read at or after our last message. A receipt outside the window is older.
        fun at(m: Message) = m.eventID?.let { position[it] }
        val last = messages.indexOfLast { it.isOwn && it.kind != MessageKind.Unsent && !it.kind.isChatEvent && at(it) != null }
        if (last >= 0) {
            val from = at(messages[last])!!
            messages[last] = messages[last].copy(isSeen = receipts.any { (user, r) -> user != me && (position[r.eventID] ?: -1) >= from })
        }
        val newest = messages.indexOfLast { !it.kind.isChatEvent && at(it) != null }
        if (newest >= 0) {
            val from = at(messages[newest])!!
            val sender = messages[newest].sender
            messages[newest] = messages[newest].copy(
                seenBy = receipts.entries.filter { it.key != me && it.key != sender && (position[it.value.eventID] ?: -1) >= from }
                    .sortedWith(compareBy({ it.value.ts }, { it.key })).map { it.key },
            )
        }
        return messages
    }

    /**
     * A live update's messages worth a notification (A8): from someone else, not an edit, newer than `since` (the
     * process's start: older ones were there at launch or came with history).
     */
    fun notifiable(events: List<Event>, me: String, since: Long): List<Event> = events.filter { e ->
        (e.type == "m.room.message" || e.type == "m.sticker") && e.sender != me && e.eventID != null &&
            e.content["m.relates_to"]["rel_type"].string != "m.replace" && (e.originServerTS ?: 0) > since
    }

    /**
     * The jump-to-newest button's count: messages from others (no chat events or tombstones) newer than `ms`, the
     * newest message's time when the button appeared.
     */
    fun newCount(messages: List<Message>, after: Long): Int =
        messages.count { !it.isOwn && !it.kind.isChatEvent && it.kind != MessageKind.Unsent && it.ts > after }

    /** "Seen by Maya", "… and Sam", "… Maya, Sam + 2", or "Seen by everyone" once all of three or more have. */
    fun seenByLine(names: List<String>, everyone: Boolean): String? = when {
        names.isEmpty() -> null
        names.size >= 3 && everyone -> "Seen by everyone"
        names.size == 1 -> "Seen by ${names[0]}"
        names.size == 2 -> "Seen by ${names[0]} and ${names[1]}"
        else -> "Seen by ${names[0]}, ${names[1]} + ${names.size - 2}"
    }

    /** The menu's Edit (the engine refuses the rest): our own text, within 15 minutes and 5 edits. */
    fun canEdit(message: Message, now: Long = System.currentTimeMillis()): Boolean =
        message.isOwn && message.eventID != null && message.kind == MessageKind.Text && message.editCount < EDIT_MAX_COUNT &&
            now - message.ts < EDIT_MAX_AGE

    /** The text to show for a content: a reply's fallback quote (`> ` lines, then a blank line) stripped. */
    fun body(content: JsonElement): String {
        val body = content["body"].string.orEmpty()
        if (replyTarget(content) == null || !body.startsWith(">")) return body
        val lines = body.split("\n").toMutableList()
        while (lines.firstOrNull()?.startsWith(">") == true) lines.removeAt(0)
        if (lines.firstOrNull()?.isEmpty() == true) lines.removeAt(0)
        return lines.joinToString("\n")
    }

    fun replyTarget(content: JsonElement): String? = content["m.relates_to"]["m.in_reply_to"]["event_id"].string

    // Layout (DESIGN §6.1)

    enum class GroupPosition { SINGLE, FIRST, MIDDLE, LAST }

    data class Row(
        val message: Message,
        val position: GroupPosition,
        /** dp above the row: 2 inside a group, 10 between groups, 16 when a time label or the unread line shows. */
        val spacing: Int,
        /** A time label above the row: the first row, and after a gap of more than an hour. */
        val showsTime: Boolean,
        /** Groups only, on someone else's message; null in DMs and on ours. */
        val groupSender: GroupSender?,
        /** The "New messages" line goes above this row. */
        val startsUnread: Boolean = false,
    ) {
        val id: String get() = message.id
    }

    /** The sender's name above the first bubble of a run, their avatar beside the last; a tombstone shows neither. */
    data class GroupSender(val showsName: Boolean, val showsAvatar: Boolean)

    /**
     * `unreadAfter`: how far we'd read when the chat opened (our receipt's ms), null when it opened read. The "New
     * messages" line goes above the first message from someone else after it and after our own last message.
     */
    fun layout(messages: List<Message>, isGroup: Boolean = false, unreadAfter: Long? = null): List<Row> {
        val firstUnread = unreadAfter?.let { after ->
            val from = maxOf(after, messages.lastOrNull { it.isOwn }?.ts ?: Long.MIN_VALUE)
            messages.indexOfFirst { !it.isOwn && it.ts > from }.takeIf { it >= 0 }
        }
        fun joins(i: Int): Boolean {
            if (i <= 0 || i >= messages.size || i == firstUnread) return false // the line starts a new run
            val a = messages[i - 1]
            val b = messages[i]
            return a.sender == b.sender && b.ts - a.ts < 180_000 && listOf(a, b).all { it.kind != MessageKind.Unsent && !it.kind.isChatEvent }
        }
        return messages.indices.map { i ->
            val joined = joins(i)
            val position = when {
                !joined && !joins(i + 1) -> GroupPosition.SINGLE
                !joined -> GroupPosition.FIRST
                joins(i + 1) -> GroupPosition.MIDDLE
                else -> GroupPosition.LAST
            }
            val showsTime = i == 0 || messages[i].ts - messages[i - 1].ts > 3_600_000
            val spacing = if (i == 0) 0 else if (joined) 2 else if (showsTime || i == firstUnread) 16 else 10
            val labelled = messages[i].kind != MessageKind.Unsent
            val first = position == GroupPosition.SINGLE || position == GroupPosition.FIRST
            val last = position == GroupPosition.SINGLE || position == GroupPosition.LAST
            val sender = if (isGroup && !messages[i].isOwn && !messages[i].kind.isChatEvent) GroupSender(labelled && first, labelled && last) else null
            Row(messages[i], position, spacing, showsTime, sender, i == firstUnread)
        }
    }

    private fun group(reactions: Collection<Event>, position: Map<String, Int>, me: String): List<Message.Reaction> {
        val groups = mutableListOf<Message.Reaction>()
        for (event in reactions.sortedBy { position[it.eventID.orEmpty()] ?: 0 }) {
            val key = event.content["m.relates_to"]["key"].string ?: continue
            val sender = event.sender ?: continue
            val mine = if (sender == me) event.eventID else null
            val i = groups.indexOfFirst { Message.Reaction.same(it.key, key) }
            if (i >= 0) groups[i] = groups[i].copy(senders = groups[i].senders + sender, myEventID = groups[i].myEventID ?: mine)
            else groups += Message.Reaction(key, listOf(sender), mine)
        }
        return groups
    }

    private fun quote(target: String, loaded: Map<String, Message>, related: Map<String, Event>): Message.Reply {
        loaded[target]?.let { return Message.Reply(target, it.sender, it.kind, it.body) }
        val event = related[target]
        val sender = event?.sender ?: return Message.Reply(target, null, null, "")
        val gone = event.isPreRedacted
        return Message.Reply(target, sender, if (gone) MessageKind.Unsent else MessageKind.of(event.type, event.content),
            if (gone) "" else body(event.content))
    }
}
