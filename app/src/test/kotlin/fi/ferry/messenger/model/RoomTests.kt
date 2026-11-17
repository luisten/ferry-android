package fi.ferry.messenger.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chat's state from its updates (the iOS app's RoomTests). Not ported, because the engine never sends them: personal
 * spaces, implicit DM names, archive tags from the server (`m.tag`).
 */
class RoomTests {
    private val dm = Fixture.event("state_bridge_dm").content

    /**
     * Android's own (R1-F2): every change bumps the room's version and every accessor reads it, so a screen handed the
     * same Room again (Compose skips it) still sees the change. An inbox row kept its old preview before.
     */
    @Test fun everyChangeIsSeenByWhatReadsTheRoom() {
        var reads = 0
        val make = Room.Version.make
        Room.Version.make = { object : Room.Version() { override var count = 0; get() = field.also { reads++ } } }
        try {
            val room = Room("!r")
            fun changes(change: () -> Unit) = room.changes.let { before -> change(); room.changes - before }
            val event = Event("m.room.message", buildJsonObject { put("msgtype", "m.text"); put("body", "hi") }, "\$1", "@a", originServerTS = 5)
            assertEquals(1, changes { room.apply(JoinedRoom(timeline = JoinedRoom.Timeline(listOf(event), limited = true, prevBatch = "p"))) })
            assertEquals(1, changes { room.prepend(MessagesPage(emptyList(), null), "p") })
            assertEquals(1, changes { room.addPending(PendingMessage("t", emptyObject, 6, PendingMessage.State.Sending)) })
            assertEquals(1, changes { room.updatePending("t", PendingMessage.State.Failed("no")) })
            assertEquals(1, changes { room.removePending("t") })
            assertEquals(1, changes { room.addRelated(event) })
            assertEquals(1, changes { room.tags = mapOf(Inbox.ARCHIVED_TAG to emptyObject) })
            assertEquals(1, changes { room.requestAction = Room.RequestAction.ACCEPT })
            // What the inbox's row reads goes through the version.
            for (read in listOf<() -> Any?>({ room.lastMessage }, { room.isUnread }, { room.name }, { room.lastActivity }, { room.tags },
                { room.typing }, { room.receipts }, { room.pending }, { room.prevBatch }, { room.reachedStart })) {
                val before = reads
                read()
                assertTrue(reads > before)
            }
        } finally {
            Room.Version.make = make
        }
    }

    // BridgeInfo

    @Test fun bridgeInfoFromARealDM() {
        val info = BridgeInfo.parse(dm)!!
        assertEquals(Fixture.kivoLogin, info.receiver)
        assertEquals(Fixture.tamoLogin, info.channelID)
        assertTrue(info.isDM)
        assertFalse(info.isRequest) // the key is absent, not false
        assertEquals("Tamo Veska", info.displayName)
        assertEquals("mxc://igm.home.arpa/onvkSvgXgcbowUuvGnImEyPS", info.avatarURL)
    }

    @Test fun requestFlag() {
        assertTrue(BridgeInfo.parse(dm.setting(listOf("channel", "com.beeper.message_request"), true))!!.isRequest)
        assertFalse(BridgeInfo.parse(dm.setting(listOf("channel", "com.beeper.message_request"), false))!!.isRequest)
    }

    /** A group carries no room type at all, which must read as a group, not as an error. */
    @Test fun missingRoomTypeIsAGroup() {
        val group = dm.setting(listOf("com.beeper.room_type"), null).setting(listOf("com.beeper.room_type.v2"), null)
        assertFalse(BridgeInfo.parse(group)!!.isDM)
    }

    @Test fun otherProtocolIsNotInstagram() {
        assertNull(BridgeInfo.parse(dm.setting(listOf("protocol", "id"), "instagram")))
        assertNull(BridgeInfo.parse(dm.setting(listOf("protocol"), null)))
    }

    // Applying updates

    @Test fun applyMergesStateFromStateAndTimeline() {
        val room = syncRoom(Fixture.tamoRoom)
        assertNotNull(room.stateEvent("m.room.create")) // from `state`
        assertEquals(Fixture.tamoLogin, room.bridge?.receiver) // from the timeline
        // Renamed Kivo Studio → Unknown user → Kivo Studio inside this batch: the last one wins.
        assertEquals("Kivo Studio", room.name)
        assertEquals("mxc://igm.home.arpa/AKRxSivqzKQzNAwknfzLHZsH", room.avatarURL)
        assertEquals(20, room.events.size)
        assertEquals("s18_1_0_8_16_1_1_11_0_1_2_1_1_1", room.prevBatch)
        assertEquals(0, room.notificationCount)
        assertFalse(room.isUnread)
        assertEquals("Kivo Studio", room.memberName(Fixture.kivoGhost))
    }

    /** A chat's `m.bridge` re-sent after Instagram moved the thread: GENERAL puts it in General. */
    @Test fun bridgeAfterAMoveCarriesTheFolder() {
        val content = Fixture.event("state_bridge_dm_after_move").content
        assertEquals(BridgeInfo.InboxFolder.PRIMARY, BridgeInfo.parse(content)?.folder)
        assertEquals(BridgeInfo.InboxFolder.GENERAL,
            BridgeInfo.parse(content.setting(listOf("channel", BridgeInfo.INBOX_FOLDER_KEY), "GENERAL"))?.folder)
    }

    @Test fun roomWithAllStateInTheTimeline() {
        val room = syncRoom(Fixture.kivoRoom)
        assertEquals(Fixture.kivoLogin, room.bridge?.receiver)
        assertEquals("Tamo Veska", room.name)
    }

    @Test fun nameAndAvatarFallBackToTheBridgeChannel() {
        val room = Room("!r")
        room.apply(stateUpdate(Fixture.event("state_bridge_dm")))
        assertEquals("Tamo Veska", room.name)
        assertEquals("mxc://igm.home.arpa/onvkSvgXgcbowUuvGnImEyPS", room.avatarURL)

        val bare = Room("!bare")
        assertEquals("Instagram chat", bare.name)
        assertNull(bare.avatarURL)
    }

    @Test fun emptyRoomNameIsIgnored() {
        val room = Room("!r")
        room.apply(stateUpdate(Fixture.event("state_bridge_dm"), state("m.room.name", mapOf("name" to ""))))
        assertEquals("Tamo Veska", room.name)
    }

    @Test fun nonLimitedBatchAppendsAndKeepsPrevBatch() {
        val room = syncRoom(Fixture.tamoRoom)
        room.apply(timeline(message("\$new", 1_800_000_000_000), prevBatch = "later"))
        assertEquals(21, room.events.size)
        assertEquals("\$new", room.events.last().eventID)
        assertEquals("s18_1_0_8_16_1_1_11_0_1_2_1_1_1", room.prevBatch)
    }

    /** A limited batch replaces the window and its prev_batch becomes ours. */
    @Test fun limitedBatchResetsTheWindow() {
        val room = syncRoom(Fixture.tamoRoom)
        room.apply(timeline(message("\$new", 1_800_000_000_000), limited = true, prevBatch = "gap"))
        assertEquals(listOf("\$new"), room.events.map { it.eventID })
        assertEquals("gap", room.prevBatch)
        assertEquals("Kivo Studio", room.name) // state survives a reset
    }

    @Test fun unreadCounts() {
        val room = syncRoom(Fixture.kivoRoom)
        room.apply(JoinedRoom(unreadNotifications = JoinedRoom.UnreadCounts(2, 0)))
        assertEquals(2, room.notificationCount)
        assertTrue(room.isUnread)
        room.apply(timeline()) // counts absent: unchanged
        assertTrue(room.isUnread)
        room.apply(JoinedRoom(unreadNotifications = JoinedRoom.UnreadCounts(0, 0)))
        assertFalse(room.isUnread)
    }

    @Test fun typingIsReplacedNotMerged() {
        val room = Room("!r")
        room.apply(JoinedRoom(ephemeral = EventList(listOf(Event("m.typing", obj("user_ids" to listOf("@a", "@b")))))))
        assertEquals(listOf("@a", "@b"), room.typing)
        room.apply(JoinedRoom(ephemeral = EventList(listOf(Event("m.typing", obj("user_ids" to emptyList<String>()))))))
        assertEquals(emptyList<String>(), room.typing)
    }

    @Test fun receiptsFromARealFixtureThenTheNewerOneWins() {
        val room = syncRoom(Fixture.tamoRoom)
        val reel = "\$mGkNbgrbORGhHJdU6tATIr2ywcDOXl0Rs32LA4iAipw"
        assertEquals(reel, room.receipts[Fixture.me]?.eventID)
        assertEquals(reel, room.receipts[Fixture.kivoGhost]?.eventID)

        val newer = obj("\$x" to mapOf("m.read" to mapOf(Fixture.me to mapOf("ts" to 1_900_000_000_000))))
        val older = obj("\$old" to mapOf("m.read" to mapOf(Fixture.me to mapOf("ts" to 1))))
        room.apply(JoinedRoom(ephemeral = EventList(listOf(Event("m.receipt", newer), Event("m.receipt", older)))))
        assertEquals("\$x", room.receipts[Fixture.me]?.eventID)
    }

    // Last activity

    /** Real: messages keep Instagram's times while later state events are stamped later; sorting must use the messages. */
    @Test fun lastActivityIsTheLastMessageNotTheLastStateEvent() {
        val room = syncRoom(Fixture.tamoRoom)
        assertEquals("m.image", room.lastMessage?.content?.get("msgtype").string)
        assertEquals(1_781_313_332_465, room.lastActivity)
        assertTrue((room.events.last().originServerTS ?: 0) > room.lastActivity)
    }

    /** The real "Load video" edit is stamped when it was resolved: an edit must not bump the chat. */
    @Test fun editDoesNotBumpButChangesCurrentContent() {
        val room = syncRoom(Fixture.tamoRoom)
        room.apply(timeline(Fixture.event("event_reel_resolve_edit")))
        assertEquals(1_781_313_332_465, room.lastActivity)
        assertEquals("m.video", room.currentContent(room.lastMessage!!)["msgtype"].string)
    }

    @Test fun unsentMessageIsSkipped() {
        val room = Room("!r")
        room.apply(timeline(message("\$a", 10), message("\$b", 20), Event("m.room.redaction", obj("redacts" to "\$b"), "\$r", "@x", originServerTS = 30)))
        assertEquals("\$a", room.lastMessage?.eventID)
        assertEquals(10L, room.lastActivity)
    }

    @Test fun roomWithoutMessagesFallsBackToTheLatestEvent() {
        val room = syncRoom("!8B3W21v2O_DdWOmLU2US75eotLLpkE2-BhMsjpPbvZ0") // a bridge-era space: state only
        assertNull(room.lastMessage)
        assertEquals(1_791_816_585_510, room.lastActivity)
    }

    // Local parts

    @Test fun pendingIsUpdatedThenRetiredByItsEcho() {
        val room = Room("!r")
        room.addPending(PendingMessage("t1", obj("msgtype" to "m.text", "body" to "x"), 1, PendingMessage.State.Sending))
        room.updatePending("t1", PendingMessage.State.Sent("\$t1"))
        assertEquals(PendingMessage.State.Sent("\$t1"), room.pending.single().state)
        room.updatePending("gone", PendingMessage.State.Failed("x")) // the echo came first: nothing to update
        room.apply(timeline(message("\$t1", 2, Fixture.me).copy(unsigned = Event.Unsigned(transactionID = "t1"))))
        assertTrue(room.pending.isEmpty())
    }
}
