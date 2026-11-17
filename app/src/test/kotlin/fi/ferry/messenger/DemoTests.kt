package fi.ferry.messenger

import fi.ferry.messenger.model.IGAdapter
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.JoinedRoom
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.MessagesPage
import fi.ferry.messenger.model.Timeline
import fi.ferry.messenger.ui.Connecting
import fi.ferry.messenger.ui.Connecting.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The demo's made-up chats say what the checks in the plan expect of them (Android's own: iOS has no such tests). */
class DemoTests {
    private val me = IGAdapter.ME
    private val now = LocalDate.of(2026, 11, 17).atTime(13, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun room(id: String, rooms: List<fi.ferry.messenger.model.Room> = Demo.rooms(now)) = rooms.first { it.id == id }

    @Test fun aReactionLogEntryMovesTheChatUpWithoutARow() {
        val ravi = room("!debug-demo-ravi")
        assertEquals("Reacted 😂 to your message", Inbox.preview(ravi, me)?.text)
        assertEquals(now - 2 * 60_000, ravi.lastActivity)
        assertTrue(ravi.isUnread)
        val messages = Timeline.reduce(ravi, me)
        assertEquals(listOf("Your order ships today"), messages.map { it.body })
        assertEquals(listOf("😂"), messages.single().reactions.map { it.key })
    }

    @Test fun adaSpansDaysAndHasEveryKindOfRow() {
        val ada = room("!debug-demo-days")
        val messages = Timeline.reduce(ada, me)
        val rows = Timeline.layout(messages)
        // A year ago, 10 days, 3 days, 23:59 two days ago, 00:01 yesterday, today.
        assertEquals(6, rows.count { it.startsDay })
        val pair = rows.filter { it.message.body == "Goodnight!" || it.message.body == "Morning already, ha" }
        assertEquals(listOf(Timeline.GroupPosition.SINGLE, Timeline.GroupPosition.SINGLE), pair.map { it.position })
        assertEquals(listOf(true, true), pair.map { it.startsDay })
        assertEquals(1, messages.count { it.kind == MessageKind.Unsent && it.isOwn })
        assertTrue(messages.last().isOwn && messages.last().isLocalEcho) // the failed one, last
        assertTrue(messages.any { it.body.startsWith("See https://lowtide.band") && !it.isOwn })
        assertTrue(messages.any { it.body == "Tickets at lowtide.band/annex." && it.isOwn })
        assertTrue(messages.any { it.body == "👍" && !it.isOwn } && messages.any { it.body == "🎉🎉" && it.isOwn })
        assertTrue(ada.isUnread.not())
    }

    @Test fun adasTodayIsNeverInTheFuture() {
        val zone = ZoneId.systemDefault()
        val midnight = LocalDate.of(2026, 11, 17).atStartOfDay(zone).toInstant().toEpochMilli()
        for (now in listOf(midnight + 30_000, midnight + 10 * 60_000, this.now)) {
            val events = room("!debug-demo-days", Demo.rooms(now)).events.filter { it.stateKey == null }
            assertTrue(events.all { it.originServerTS!! <= now })
            assertEquals(15, events.count { it.originServerTS!! >= midnight }) // still all today
        }
    }

    @Test fun elenasPhotoHasNoURL() {
        val elena = Timeline.reduce(room("!debug-demo-4"), me)
        assertEquals(MessageKind.Image, elena.first().kind)
        assertNull(fi.ferry.messenger.model.MediaInfo(elena.first().content).url)
    }

    @Test fun theGroupHasIOSsReactions() {
        val group = Timeline.reduce(room("!debug-group"), me)
        fun reactions(id: String) = group.first { it.id == id }.reactions.map { it.key to it.senders.size }
        assertEquals(listOf("❤" to 3, "😂" to 1), reactions("\$debug-group-msg-2"))
        assertEquals(listOf("❤️" to 3, "🔥" to 1, "😂" to 1), reactions("\$debug-group-msg-4"))
        assertEquals(listOf("👍" to 3), reactions("\$debug-group-msg-6"))
    }

    @Test fun groupExtraAddsUnreadMessagesAfterTheNewest() {
        val group = room("!debug-group", Demo.rooms(now, groupExtra = 14))
        assertEquals(15, group.notificationCount)
        assertEquals(14, Timeline.reduce(group, me).count { it.id.startsWith("\$debug-group-extra-") })
    }

    @Test fun groupOlderOpensFromFiveAndPagesBack() {
        val group = room("!debug-group")
        val rest = Demo.mailboxCopy(group, pages = 3)
        assertEquals("debug-older", group.prevBatch)
        assertFalse(group.reachedStart)
        assertEquals(5, group.events.count { it.stateKey == null })
        // What the spinner loads: the group's own earlier messages, then 3 pages of 20 before them (oldest first).
        assertEquals(60, rest.count { it.eventID!!.startsWith("\$debug-group-older-") })
        assertTrue(rest.first().eventID!!.startsWith("\$debug-group-older-"))
        group.prepend(MessagesPage(rest.takeLast(20).asReversed(), "debug-older-${rest.size - 20}"), "debug-older")
        assertEquals("debug-older-${rest.size - 20}", group.prevBatch)
    }

    @Test fun aLiveReactionIsUnreadAndNeverNotifies() {
        val (id, events, unread) = Demo.reaction(now)
        val jonas = room(id)
        assertFalse(jonas.isUnread)
        jonas.apply(JoinedRoom(timeline = JoinedRoom.Timeline(events, limited = false), unreadNotifications = unread))
        assertTrue(jonas.isUnread)
        assertEquals("Reacted 😮 to your message", Inbox.preview(jonas, me)?.text)
        assertEquals(1, Timeline.reduce(jonas, me).first().reactions.size)
        assertTrue(Timeline.notifiable(events, me, now - 1).isEmpty())
    }

    @Test fun connectingShowsTamoSyncedOrKivoFailed() {
        val tamo = IGAdapter.login(Demo.connecting(signedOut = false), Demo.viewers[Demo.TAMO], "CONNECTED", since = now)
        assertEquals(Demo.TAMO, tamo.id)
        assertEquals(listOf(Step.DONE, Step.DONE, Step.DONE), Connecting.steps(tamo.health))
        val chats = Demo.rooms(now).count { it.bridge?.receiver == Demo.TAMO && Inbox.isVisible(it, Demo.viewers.keys) }
        assertEquals("7 chats", Connecting.chats(chats, tamo.health)) // Tamo's chats and requests in the demo
        val kivo = IGAdapter.login(Demo.connecting(signedOut = true), Demo.viewers[Demo.KIVO], "BAD_CREDENTIALS", since = now)
        assertEquals(Demo.KIVO, kivo.id)
        assertEquals(listOf(Step.DONE, Step.FAILED, Step.WAITING), Connecting.steps(kivo.health))
        assertEquals("BAD_CREDENTIALS", Connecting.details(kivo))
    }
}
