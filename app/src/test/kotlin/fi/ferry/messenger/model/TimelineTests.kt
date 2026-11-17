package fi.ferry.messenger.model

import fi.ferry.messenger.model.Timeline.GroupPosition.FIRST
import fi.ferry.messenger.model.Timeline.GroupPosition.LAST
import fi.ferry.messenger.model.Timeline.GroupPosition.MIDDLE
import fi.ferry.messenger.model.Timeline.GroupPosition.SINGLE
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The thread's fold, one test per rule with its counter-case (the iOS app's TimelineTests). Real fixtures where they
 * exist; hand-made events say so. Not ported: the bridge's send statuses ("delivered", failures) and
 * `receiptTarget` (the engine picks "Seen"'s message).
 */
class TimelineTests {
    private val me = Fixture.me
    private val ghost = Fixture.kivoGhost
    private val reel = MessageKind.Share(MessageKind.ShareType.REEL, MessageKind.ShareState.READY)
    private val unresolvedReel = MessageKind.Share(MessageKind.ShareType.REEL, MessageKind.ShareState.UNRESOLVED)

    // Base messages

    /** Real sync: 3 messages among ~17 state events. State is ignored, order is the timeline's. */
    @Test fun baseMessagesInTimelineOrderStateIgnored() {
        val messages = Timeline.reduce(syncRoom(Fixture.tamoRoom), me)
        assertEquals(listOf("test", "hello"), messages.map { it.body }.take(2))
        assertEquals(3, messages.size)
        assertEquals(listOf(false, true, false), messages.map { it.isOwn })
        assertEquals(MessageKind.Text, messages[0].kind)
        assertEquals(unresolvedReel, messages[2].kind)
        assertEquals(messages[0].eventID, messages[0].id)
    }

    @Test fun unknownEventTypesAreIgnored() {
        val events = listOf(message("\$a", 1), Event("com.example.thing", obj("body" to "x"), "\$b", ghost))
        assertEquals(listOf("\$a"), Timeline.reduce(events, me).map { it.id })
    }

    // Edits

    /** Real "Load video" edit: m.image → m.video in place, no "Edited" (`dont_render_edited` on the edit's top level). */
    @Test fun resolveEditReplacesMsgtypeWithoutEditedLabel() {
        val messages = Timeline.reduce(Fixture.messages("messages_dm_backfill").chunk.reversed(), me)
        val last = messages.last()
        assertEquals(reel, last.kind)
        assertEquals("m.video", last.content["msgtype"].string)
        assertEquals("mxc://igm.home.arpa/qfzfnZlXNGlgiHYGVUpxmyYw", last.content["url"].string)
        assertFalse(last.isEdited)
        assertEquals(3, messages.size) // the edit itself is not a row
    }

    /** Counter-case: the same flag inside `m.new_content` doesn't count. */
    @Test fun dontRenderEditedInsideNewContentStillShowsEdited() {
        val fixture = Fixture.event("event_reel_resolve_edit")
        val edit = fixture.copy(content = fixture.content.setting(listOf("com.beeper.dont_render_edited"), null)
            .setting(listOf("m.new_content", "com.beeper.dont_render_edited"), true))
        assertTrue(Timeline.reduce(listOf(Fixture.event("event_reel_unresolved"), edit), me).first().isEdited)
    }

    /** An older page can deliver an edit before its target: it's held and applies once the target loads. */
    @Test fun editBeforeTargetIsHeldUntilTargetLoads() {
        val edit = Fixture.event("event_reel_resolve_edit")
        val target = Fixture.event("event_reel_unresolved")
        assertEquals(emptyList<Message>(), Timeline.reduce(listOf(edit), me))
        val messages = Timeline.reduce(listOf(edit, target), me)
        assertEquals(listOf(target.eventID), messages.map { it.id })
        assertEquals(reel, messages.first().kind)
    }

    /** Counter-case: only the original sender can edit. */
    @Test fun editFromAnotherSenderIsIgnored() {
        val edit = Fixture.event("event_reel_resolve_edit").copy(sender = me)
        val messages = Timeline.reduce(listOf(Fixture.event("event_reel_unresolved"), edit), me)
        assertEquals(unresolvedReel, messages.first().kind)
        assertFalse(messages.first().isEdited)
    }

    /** Hand-made: the latest edit by timeline order wins, and each distinct edit counts toward the 5-edit limit. */
    @Test fun latestTextEditWinsAndCounts() {
        val events = listOf(
            message("\$a", 1, me, "helo"),
            edit("\$e1", "\$a", 2, me, "hello"),
            edit("\$e2", "\$a", 3, me, "hello!"),
        )
        val m = Timeline.reduce(events, me)[0]
        assertEquals("hello!", m.body)
        assertTrue(m.isEdited)
        assertEquals(2, m.editCount)
        assertEquals(1L, m.ts) // keeps the original's time and place
    }

    /** Real: our edit, then Instagram's echo of it with the same text: one edit toward the limit, not two. */
    @Test fun realEditAndItsEchoCountOnce() {
        val edits = Fixture.events("events_m4_tamo").filter { it.replacesEventID == M4_MESSAGE }
        assertEquals(2, edits.size)
        val m = Timeline.reduce(listOf(restoredM4Message()) + edits, me)[0]
        assertEquals("M4 check: hello from tamoveska (edited)", m.body)
        assertTrue(m.isEdited)
        assertEquals(1, m.editCount)
    }

    @Test fun redactedEditFallsBackToThePreviousOne() {
        val events = listOf(
            message("\$a", 1, me, "one"),
            edit("\$e1", "\$a", 2, me, "two"),
            edit("\$e2", "\$a", 3, me, "three"),
            redaction("\$r", "\$e2", 4),
        )
        val m = Timeline.reduce(events, me)[0]
        assertEquals("two", m.body)
        assertEquals(1, m.editCount)
    }

    @Test fun editToARedactedTargetIsIgnored() {
        val events = listOf(message("\$a", 1, me, "one"), redaction("\$r", "\$a", 2), edit("\$e1", "\$a", 3, me, "two"))
        val m = Timeline.reduce(events, me)[0]
        assertEquals(MessageKind.Unsent, m.kind)
        assertEquals("", m.body)
        assertFalse(m.isEdited)
    }

    @Test fun canEditOwnTextWithinInstagramLimits() {
        val start = 1_000_000L
        val now = start + 60_000
        fun sent(sender: String = me, edits: Int = 0, kind: String = "m.text"): Message {
            val events = listOf(message("\$a", start, sender, msgtype = kind)) +
                (0 until edits).map { edit("\$e$it", "\$a", start + 1, sender, "$it") }
            return Timeline.reduce(events, me)[0]
        }
        assertTrue(Timeline.canEdit(sent(), now))
        assertTrue(Timeline.canEdit(sent(edits = 4), now))
        assertFalse(Timeline.canEdit(sent(edits = 5), now)) // at most 5 edits
        assertFalse(Timeline.canEdit(sent(), now + 15 * 60_000)) // within 15 minutes
        assertFalse(Timeline.canEdit(sent(sender = ghost), now))
        assertFalse(Timeline.canEdit(sent(kind = "m.image"), now))
    }

    // Reactions

    @Test fun reactionsGroupByKey() {
        val events = listOf(
            message("\$a", 1),
            reaction("\$r1", "\$a", "❤️", ghost),
            reaction("\$r2", "\$a", "❤️", me),
            reaction("\$r3", "\$a", "😂", "@instagram_3:igm.home.arpa"),
        )
        val reactions = Timeline.reduce(events, me)[0].reactions
        assertEquals(listOf("❤️", "😂"), reactions.map { it.key })
        assertEquals(listOf(2, 1), reactions.map { it.count })
        assertEquals("\$r2", reactions[0].myEventID)
        assertNull(reactions[1].myEventID)
    }

    /**
     * Only a send from this phone takes the thread to its end (`Message.isLocalEcho`): our own message sent from
     * Instagram's app arrives as an event (with its offline threading ID, like any of ours) and leaves the scroll alone.
     */
    @Test fun onlyWhatWeSendFromHereIsALocalEcho() {
        val elsewhere = Event("m.room.message", obj("msgtype" to "m.text", "body" to "from the Instagram app"), "\$own", me,
            originServerTS = 1, unsigned = Event.Unsigned(transactionID = "751"))
        val messages = Timeline.reduce(listOf(elsewhere), me, pending = listOf(
            PendingMessage("t1", obj("msgtype" to "m.text", "body" to "sending"), 2, PendingMessage.State.Sending),
            PendingMessage("t2", obj("msgtype" to "m.text", "body" to "sent, not back yet"), 3, PendingMessage.State.Sent("\$t2")),
        ))
        assertEquals(listOf(true, true, true), messages.map { it.isOwn })
        assertEquals(listOf(false, true, true), messages.map { it.isLocalEcho })
    }

    /** A8: only a new message from someone else notifies; not ours, an edit, a reaction or what came before launch. */
    @Test fun notifiableMessages() {
        val since = 100L
        val events = listOf(
            message("\$new", 200, sender = ghost),
            message("\$old", 50, sender = ghost),
            message("\$mine", 200, sender = me),
            Event("m.room.message", obj("msgtype" to "m.text", "body" to "* fixed", "m.relates_to" to mapOf("rel_type" to "m.replace", "event_id" to "\$new")),
                "\$edit", ghost, originServerTS = 210),
            reaction("\$r", "\$new", "❤", ghost, ts = 220),
            // Android's own (D2): iOS has no notifications. A reaction log entry makes the chat unread but never notifies.
            message("\$log", 230, sender = ghost, msgtype = "m.notice", extra = mapOf(MessageKind.REACTION_LOG_KEY to true)),
        )
        assertEquals(listOf("\$new"), Timeline.notifiable(events, me, since).map { it.eventID })
    }

    /** The jump-to-newest button's count: what others sent after it appeared, never ours, chat lines or unsent ones. */
    @Test fun newCountIsOnlyWhatOthersSentSince() {
        fun message(id: String, ts: Long, own: Boolean = false, kind: MessageKind = MessageKind.Text) =
            Message(id, sender = if (own) me else ghost, ts = ts, kind = kind, body = id, content = obj(), isOwn = own)
        val messages = listOf(
            message("before", 100), message("since", 200), message("mine", 300, own = true),
            message("left the group", 400, kind = MessageKind.ChatEvent), message("poll", 500, kind = MessageKind.Unsupported("a poll")),
            message("unsent", 600, kind = MessageKind.Unsent), message("photo", 700, kind = MessageKind.Image),
        )
        assertEquals(2, Timeline.newCount(messages, after = 100)) // "since" and the photo
        assertEquals(0, Timeline.newCount(messages, after = 700))
    }

    /** The long press's reactions (iOS's ReactionPickerTests): the last six used, in place; ours always there. */
    @Test fun recentsStayInPlaceAndTheNewestGoesFirst() {
        assertEquals(listOf("❤️", "😂", "😮", "😢", "🔥", "👍"), RecentReactions.row(null))
        assertEquals(listOf("🙈", "❤️", "😂", "😮", "😢", "🔥"), RecentReactions.row(listOf("🙈"))) // the defaults pad it
        val row = RecentReactions.row(null)
        assertEquals(row, RecentReactions.adding("🔥", row)) // already there: nothing moves under the thumb
        assertEquals(row, RecentReactions.adding("❤", row)) // Instagram's heart is the same heart
        assertEquals(listOf("🙈", "❤️", "😂", "😮", "😢", "🔥"), RecentReactions.adding("🙈", row))
    }

    @Test fun ourReactionIsShownEvenWhenItIsNotRecent() {
        val row = RecentReactions.row(null)
        assertEquals(listOf("❤️", "😂", "😮", "😢", "🔥", "🦖"), RecentReactions.shown(row, "🦖"))
        assertEquals(row, RecentReactions.shown(row, "❤"))
        assertEquals(row, RecentReactions.shown(row, null))
    }

    /** A reaction is the same with or without its variation selector: we send "❤️", Instagram echoes "❤". */
    @Test fun instagramsHeartIsOurs() {
        val events = listOf(
            message("\$m", 1),
            reaction("\$r1", "\$m", "❤", me),
            reaction("\$r2", "\$m", "❤️", ghost),
        )
        val reduced = Timeline.reduce(events, me)[0]
        assertEquals(1, reduced.reactions.size) // one heart, two people
        assertEquals(listOf(me, ghost), reduced.reactions[0].senders)
        assertTrue(reduced.reacted("❤️", me)) // the palette's heart: choosing it again removes ours
        assertFalse(reduced.reacted("😂", me))
    }

    /** Instagram allows one reaction per person: only each sender's latest counts, even if the old one stayed. */
    @Test fun onlyTheLatestReactionPerSenderCounts() {
        val events = listOf(
            message("\$a", 1),
            reaction("\$r1", "\$a", "❤️", me),
            reaction("\$r2", "\$a", "❤️", ghost),
            reaction("\$r3", "\$a", "😂", me),
        )
        val reactions = Timeline.reduce(events, me)[0].reactions
        assertEquals(listOf("❤️", "😂"), reactions.map { it.key })
        assertEquals(listOf(listOf(ghost), listOf(me)), reactions.map { it.senders })
    }

    @Test fun redactedReactionIsDropped() {
        val events = listOf(message("\$a", 1), reaction("\$r1", "\$a", "❤️", me), redaction("\$x", "\$r1", 5))
        assertEquals(emptyList<Message.Reaction>(), Timeline.reduce(events, me)[0].reactions)
    }

    /**
     * Real ❤️ → 😂 change, where the replaced reaction was redacted and a duplicate raced its replacement. Redacted
     * reactions are restored to the content sent. One 😂 must remain, whichever way the race orders them.
     */
    @Test fun realReactionChangeLeavesOneReaction() {
        val reply = "\$ioTZwPw6EpU8U0CoxD32g1RW2tAyxz0SuOz69ZqQEas"
        val restored = mapOf("\$pD-zIZv_PfJp-lFje0fIEN85dLvT0Rt63ERB3F6VMlE" to "❤️", "\$-83a67EsVq5pTTNdtJpY4k291xwJxfJn0J_LuTHuOOs" to "😂")
        val events = Fixture.events("events_m4_tamo").map { e ->
            e.eventID?.let(restored::get)?.let { key ->
                e.copy(content = obj("m.relates_to" to mapOf("rel_type" to "m.annotation", "event_id" to reply, "key" to key)), unsigned = null)
            } ?: e
        }.toMutableList()
        val expected = listOf(Message.Reaction("😂", listOf(me), "\$WgeKIHZLqBVREiDs9RnNHi4986Ufij7HmzMok-CLtX8"))
        assertEquals(expected, Timeline.reduce(events, me).first { it.id == reply }.reactions)

        // The race the other way: the surviving 😂 lands before the duplicate that is then redacted.
        val moved = events.removeAt(events.indexOfFirst { it.eventID == expected[0].myEventID })
        events.add(events.indexOfFirst { it.eventID == "\$-83a67EsVq5pTTNdtJpY4k291xwJxfJn0J_LuTHuOOs" }, moved)
        assertEquals(expected, Timeline.reduce(events, me).first { it.id == reply }.reactions)
    }

    /** Real, the other side: kivostudio's reply carries one 😂 from tamoveska (its ❤️ arrives already redacted). */
    @Test fun realReactionOnTheOtherSide() {
        val reply = Timeline.reduce(Fixture.events("events_m4_kivo"), me).first { it.body == "M4 reply from kivostudio" }
        assertEquals(listOf("😂"), reply.reactions.map { it.key })
        assertEquals(listOf(listOf(Fixture.tamoGhost)), reply.reactions.map { it.senders })
        assertNull(reply.reactions[0].myEventID)
    }

    @Test fun reactionToAnUnknownEventIsIgnored() {
        val events = listOf(message("\$a", 1), reaction("\$r1", "\$elsewhere", "❤️", me))
        assertEquals(listOf(emptyList<Message.Reaction>()), Timeline.reduce(events, me).map { it.reactions })
    }

    // Unsends

    /** `content.redacts` names the target; the tombstone keeps who unsent it. */
    @Test fun redactionMakesATombstoneThatKeepsItsSender() {
        val events = listOf(message("\$a", 1, ghost), message("\$b", 2, me), redaction("\$r", "\$a", 3), redaction("\$r2", "\$b", 4))
        val messages = Timeline.reduce(events, me)
        assertEquals(listOf(MessageKind.Unsent, MessageKind.Unsent), messages.map { it.kind })
        assertEquals(listOf(ghost, me), messages.map { it.sender })
    }

    /** Real unsend, fetched afterwards: already redacted, its two edits ignored, and the reply quoting it says so. */
    @Test fun realUnsentMessageIsATombstoneAndItsQuoteFollows() {
        val messages = Timeline.reduce(Fixture.events("events_m4_tamo"), me)
        assertEquals(listOf(M4_MESSAGE, "\$ioTZwPw6EpU8U0CoxD32g1RW2tAyxz0SuOz69ZqQEas"), messages.map { it.id })
        assertEquals(MessageKind.Unsent, messages[0].kind)
        assertTrue(messages[0].isOwn)
        assertFalse(messages[0].isEdited)
        assertEquals(MessageKind.Unsent, messages[1].reply?.kind)
        assertEquals("M4 reply from kivostudio", messages[1].body)
    }

    /** The same unsend seen live (target loaded, then the redaction); without the redaction it's the edited text. */
    @Test fun realUnsendOfALoadedMessage() {
        val events = Fixture.events("events_m4_tamo")
        val unsend = events.first { it.redactionTarget == M4_MESSAGE }
        val edits = events.filter { it.replacesEventID == M4_MESSAGE }
        assertEquals(listOf(MessageKind.Unsent), Timeline.reduce(listOf(restoredM4Message()) + edits + unsend, me).map { it.kind })
        assertEquals(listOf(MessageKind.Text), Timeline.reduce(listOf(restoredM4Message()) + edits, me).map { it.kind })
    }

    @Test fun tombstoneDropsItsReactions() {
        val events = listOf(message("\$a", 1), reaction("\$r1", "\$a", "❤️", me), redaction("\$x", "\$a", 5))
        assertEquals(emptyList<Message.Reaction>(), Timeline.reduce(events, me)[0].reactions)
    }

    @Test fun redactionOfAnUnknownEventChangesNothing() {
        assertEquals(listOf(MessageKind.Text), Timeline.reduce(listOf(message("\$a", 1), redaction("\$x", "\$nope", 5)), me).map { it.kind })
    }

    // Replies

    /** Hand-made: the engine sends no reply fallback, but one is stripped all the same. */
    @Test fun replyQuotesTheLoadedTargetAndStripsTheFallback() {
        val events = listOf(
            message("\$a", 1, ghost, "see you there"),
            message("\$b", 2, me, "> <$ghost> see you there\n\nyes!", extra = mapOf("m.relates_to" to mapOf("m.in_reply_to" to mapOf("event_id" to "\$a")))),
        )
        val reply = Timeline.reduce(events, me)[1]
        assertEquals("yes!", reply.body)
        assertEquals(Message.Reply("\$a", ghost, MessageKind.Text, "see you there"), reply.reply)
    }

    /** Real reply (kivostudio replying to our message), quoting the target as edited. */
    @Test fun realReplyQuotesItsTarget() {
        val events = Fixture.events("events_m4_tamo")
        val reply = events.first { it.eventID == "\$ioTZwPw6EpU8U0CoxD32g1RW2tAyxz0SuOz69ZqQEas" }
        val edits = events.filter { it.replacesEventID == M4_MESSAGE }
        val m = Timeline.reduce(listOf(restoredM4Message(), reply) + edits, me).last()
        assertEquals(Fixture.kivoGhost, m.sender)
        assertEquals(Message.Reply(M4_MESSAGE, me, MessageKind.Text, "M4 check: hello from tamoveska (edited)"), m.reply)
    }

    /** Not loaded: a placeholder, filled from `related` once it's there. */
    @Test fun replyToAnUnloadedTargetIsAPlaceholderUntilFetched() {
        val reply = message("\$b", 2, me, "yes!", extra = mapOf("m.relates_to" to mapOf("m.in_reply_to" to mapOf("event_id" to "\$old"))))
        val placeholder = Timeline.reduce(listOf(reply), me)[0].reply
        assertEquals("\$old", placeholder?.eventID)
        assertEquals(false, placeholder?.isLoaded)

        val fetched = message("\$old", 1, ghost, "from last week")
        val loaded = Timeline.reduce(listOf(reply), me, related = mapOf("\$old" to fetched))[0].reply
        assertEquals("from last week", loaded?.body)
        assertEquals(true, loaded?.isLoaded)
    }

    /** The quote shows the target as it is now: edited text, or unsent. */
    @Test fun replyQuoteFollowsEditsAndUnsend() {
        fun replyTo(id: String) = message("\$b$id", 5, me, "ok", extra = mapOf("m.relates_to" to mapOf("m.in_reply_to" to mapOf("event_id" to id))))
        val events = listOf(
            message("\$a", 1, ghost, "helo"),
            edit("\$e", "\$a", 2, ghost, "hello"),
            message("\$c", 3, ghost, "oops"),
            redaction("\$r", "\$c", 4),
            replyTo("\$a"), replyTo("\$c"),
        )
        val messages = Timeline.reduce(events, me)
        assertEquals("hello", messages[2].reply?.body)
        assertEquals(MessageKind.Unsent, messages[3].reply?.kind)
    }

    /** Counter-case: a message that merely starts with "> " (a quote someone typed) keeps it. */
    @Test fun quoteWithoutReplyRelationIsKept() {
        val events = listOf(message("\$a", 1, body = "> famous words\n\nindeed"))
        assertEquals("> famous words\n\nindeed", Timeline.reduce(events, me)[0].body)
        assertNull(Timeline.reduce(events, me)[0].reply)
    }

    // Local echo

    @Test fun pendingSendsComeAfterServerEvents() {
        val pending = listOf(
            PendingMessage("t1", obj("msgtype" to "m.text", "body" to "on its way"), 0, PendingMessage.State.Sending),
            PendingMessage("t2", obj("msgtype" to "m.text", "body" to "sent"), 0, PendingMessage.State.Sent("\$t2")),
            PendingMessage("t3", obj("msgtype" to "m.text", "body" to "nope"), 0, PendingMessage.State.Failed("offline")),
        )
        val messages = Timeline.reduce(listOf(message("\$a", 9)), me, pending)
        assertEquals(listOf("\$a", "txn:t1", "txn:t2", "txn:t3"), messages.map { it.id })
        assertEquals(listOf(Message.Status.Sending, Message.Status.Sent, Message.Status.Failed("offline")), messages.drop(1).map { it.status })
        assertEquals("\$t2", messages[2].eventID)
        assertTrue(messages[1].isOwn)
        assertEquals("on its way", messages[1].body)
    }

    /** Real: the echo carries `unsigned.transaction_id`, which retires the pending item. */
    @Test fun pendingIsReplacedByItsEcho() {
        val events = Fixture.events("events_send_status")
        val txn = events[0].unsigned?.transactionID!!
        val pending = listOf(PendingMessage(txn, obj("msgtype" to "m.text", "body" to "igm test (M3)"), 0, PendingMessage.State.Sending))
        assertEquals(listOf(events[0].eventID), Timeline.reduce(events, me, pending).map { it.id })
        // Counter-case: another transaction stays pending.
        val other = listOf(PendingMessage("other", obj("msgtype" to "m.text", "body" to "x"), 0, PendingMessage.State.Sending))
        assertEquals(2, Timeline.reduce(events, me, other).size)
    }

    // Seen

    /** Real sync: kivostudio read up to its reel, which comes after our "hello", so "hello" is Seen. */
    @Test fun seenFromTheirReceiptAtOrAfterOurLastMessage() {
        assertEquals(listOf("hello"), Timeline.reduce(syncRoom(Fixture.tamoRoom), me).filter { it.isSeen }.map { it.body })
    }

    /** Real sync, counter-case: in kivostudio's chat only our own receipt exists, so nothing is Seen. */
    @Test fun ownReceiptIsNotSeen() {
        assertEquals(emptyList<Message>(), Timeline.reduce(syncRoom(Fixture.kivoRoom), me).filter { it.isSeen })
    }

    @Test fun receiptBeforeOurLastMessageIsNotSeen() {
        val events = listOf(message("\$a", 1, ghost), message("\$b", 2, me), message("\$c", 3, me))
        assertEquals(emptyList<Message>(), Timeline.reduce(events, me, receipts = mapOf(ghost to Room.Receipt("\$b", 9))).filter { it.isSeen })
        assertEquals(listOf("\$c"), Timeline.reduce(events, me, receipts = mapOf(ghost to Room.Receipt("\$c", 9))).filter { it.isSeen }.map { it.id })
        assertEquals(emptyList<Message>(), Timeline.reduce(events, me, receipts = mapOf(ghost to Room.Receipt("\$gone", 9))).filter { it.isSeen })
    }

    // Classification

    @Test fun classification() {
        fun kind(msgtype: String, extra: Map<String, Any?> = emptyMap(), type: String = "m.room.message") =
            MessageKind.of(type, j(mapOf("msgtype" to msgtype, "body" to "x") + extra))
        val link = "https://www.instagram.com/reel/DSampleRl01/?id=1"
        val ready = MessageKind.ShareState.READY
        assertEquals(unresolvedReel, MessageKind.of("m.room.message", Fixture.event("event_reel_unresolved").content))
        assertEquals(MessageKind.Share(MessageKind.ShareType.REEL, ready), kind("m.video", mapOf("external_url" to link)))
        assertEquals(MessageKind.Share(MessageKind.ShareType.POST, ready), kind("m.image", mapOf("external_url" to "https://www.instagram.com/p/abc/")))
        assertEquals(MessageKind.Share(MessageKind.ShareType.STORY, ready), kind("m.image", mapOf("external_url" to "https://www.instagram.com/stories/maya/123/")))
        assertEquals(MessageKind.Voice, kind("m.audio", mapOf("org.matrix.msc3245.voice" to emptyMap<String, Any>())))
        assertEquals(MessageKind.Audio, kind("m.audio"))
        assertEquals(MessageKind.Image, kind("m.image"))
        assertEquals(MessageKind.Text, kind("m.text", mapOf("external_url" to link))) // a link in a text is a text
        assertEquals(MessageKind.Notice, kind("m.notice"))
        assertEquals(MessageKind.Text, kind("m.whatever")) // unknown msgtypes fall back to their body
        assertEquals(MessageKind.Sticker, MessageKind.of("m.sticker", obj("body" to "x")))
    }

    // Layout (DESIGN §6.1 grouping)

    @Test fun groupingBySenderWithinThreeMinutes() {
        val minute = 60_000L
        val events = listOf(
            message("\$1", 0, ghost),
            message("\$2", minute, ghost),
            message("\$3", 2 * minute, ghost),
            message("\$4", 3 * minute, me),
            message("\$5", 7 * minute, me), // 4 minutes later: a new group
            message("\$6", 70 * minute, me), // over an hour, same day: no chip
            message("\$7", 70 * minute + 86_400_000, me), // the next day: a chip
        )
        val rows = Timeline.layout(Timeline.reduce(events, me), zone = ZoneOffset.UTC)
        assertEquals(listOf(FIRST, MIDDLE, LAST, SINGLE, SINGLE, SINGLE, SINGLE), rows.map { it.position })
        assertEquals(listOf(true, false, false, false, false, false, true), rows.map { it.startsDay })
        assertEquals(listOf(0, 2, 2, 10, 10, 10, 16), rows.map { it.spacing })
    }

    /** A day chip breaks a run even inside three minutes: 23:59 then 00:01. */
    @Test fun aDayChipStartsANewRun() {
        val midnight = 86_400_000L
        val events = listOf(message("\$1", midnight - 60_000, me), message("\$2", midnight + 60_000, me))
        val rows = Timeline.layout(Timeline.reduce(events, me), zone = ZoneOffset.UTC)
        assertEquals(listOf(SINGLE, SINGLE), rows.map { it.position })
        assertEquals(listOf(true, true), rows.map { it.startsDay })
    }

    /** TalkBack's label (iOS's `testGroupThreadShowsSendersAndEvents` checks "From Theo Park, …" in its UI test). */
    @Test fun spokenLabels() {
        val minute = 60_000L
        val events = listOf(message("\$1", 0, ghost), message("\$2", minute, ghost), message("\$3", 2 * minute, me))
        fun rows(group: Boolean) = Timeline.layout(Timeline.reduce(events, me), isGroup = group, zone = ZoneOffset.UTC)

        // A DM: the sender where a run starts (the first row also starts the day), the text alone inside it.
        val dm = rows(false)
        assertEquals("From Kivo: hi, 9:41", Timeline.spokenLabel(dm[0], "hi", "9:41", "Tamo", "Kivo"))
        assertEquals("hi, 9:42", Timeline.spokenLabel(dm[1], "hi", "9:42", "Tamo", "Kivo"))
        assertEquals("From you, as Tamo: hi, 9:43", Timeline.spokenLabel(dm[2].copy(startsDay = true), "hi", "9:43", "Tamo", "Kivo"))
        assertEquals("You: hi, 9:43", Timeline.spokenLabel(dm[2].copy(startsDay = false), "hi", "9:43", "Tamo", "Kivo"))
        assertEquals("From you, as you: hi, 9:43", Timeline.spokenLabel(dm[2].copy(startsDay = true), "hi", "9:43", null, "Kivo"))
        assertEquals("Kivo: hi, 9:41", Timeline.spokenLabel(dm[0].copy(startsDay = false), "hi", "9:41", "Tamo", "Kivo"))

        // A group: every incoming bubble names its sender, inside a run too; ours as in a DM.
        val group = rows(true)
        assertEquals("From Theo Park, hi, 9:41", Timeline.spokenLabel(group[0], "hi", "9:41", "Tamo", "Theo Park"))
        assertEquals("From Theo Park, hi, 9:42", Timeline.spokenLabel(group[1], "hi", "9:42", "Tamo", "Theo Park"))
        assertEquals("From you, as Tamo: hi, 9:43", Timeline.spokenLabel(group[2].copy(startsDay = true), "hi", "9:43", "Tamo", "Theo Park"))

        // A card's: the sender alone, none inside a DM's run (the card says "Photo" and its time itself).
        assertEquals("From Kivo", Timeline.spokenSender(dm[0], "Tamo", "Kivo"))
        assertNull(Timeline.spokenSender(dm[1], "Tamo", "Kivo"))
        assertEquals("From Theo Park", Timeline.spokenSender(group[1], "Tamo", "Theo Park"))
    }

    @Test fun dayLabels() {
        val now = 1_794_300_000_000L // Tue 10 Nov 2026, 08:40 UTC
        fun label(daysAgo: Int) = Timeline.dayLabel(now - daysAgo * 86_400_000L, now, ZoneOffset.UTC)
        assertEquals("Today", label(0))
        assertEquals("Yesterday", label(1))
        assertEquals(DateTimeFormatter.ofPattern("EEEE", Locale.ROOT).format(Instant.ofEpochMilli(now - 3 * 86_400_000L).atZone(ZoneOffset.UTC)), label(3))
        assertFalse(label(8).contains("2026"))
        assertTrue(label(400).contains("2025"))
        assertEquals("Tue 3 Nov", label(7)) // a week back is a date, not a weekday
        assertEquals("Wed 11 Nov", Timeline.dayLabel(now + 86_400_000, now, ZoneOffset.UTC)) // the future is a date too
    }

    @Test fun eventsDecodeAsTheEngineAndTheBridgeSendThem() {
        val events = Fixture.sync().rooms?.join?.get(Fixture.tamoRoom)?.timeline?.events!!
        val bridge = events.last { it.type == "m.bridge" }
        assertEquals("igm.home.arpa/instagram", bridge.stateKey)
        assertEquals("instagramgo", bridge.content["protocol"]["id"].string)
        val hello = events.first { it.content["body"].string == "hello" }
        assertEquals(me, hello.sender)
        assertEquals(1_781_264_377_286, hello.originServerTS)
        assertNull(hello.stateKey) // message events have no state key; "" would mean state
        assertNotNull(json.decodeFromString<Event>("""{"type":"m.typing"}""").content.obj) // content always there
        val edit = Fixture.event("event_reel_resolve_edit")
        assertEquals(Fixture.event("event_reel_unresolved").eventID, edit.replacesEventID)
        assertEquals("\$a", Event("m.room.redaction", obj("redacts" to "\$a")).redactionTarget)
        assertNull(Event("m.room.message", obj("redacts" to "\$c")).redactionTarget)
    }

    private companion object {
        /** Our "M4 check" message in tamoveska's chat (unsent at the end of the live run). */
        const val M4_MESSAGE = "\$0rZGiVgg_YxNGWc8Fb7DUKHdZg0WD2gkM6Bo-Gon44Y"

        /** The real pre-redacted event with the content it was sent with restored (an unsend strips it). */
        fun restoredM4Message(): Event = Fixture.events("events_m4_tamo").first { it.eventID == M4_MESSAGE }
            .copy(content = obj("msgtype" to "m.text", "body" to "M4 check: hello from tamoveska"), unsigned = null)
    }
}
