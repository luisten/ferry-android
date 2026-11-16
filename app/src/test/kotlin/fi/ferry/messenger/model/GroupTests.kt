package fi.ferry.messenger.model

import fi.ferry.messenger.model.Timeline.GroupPosition.FIRST
import fi.ferry.messenger.model.Timeline.GroupPosition.LAST
import fi.ferry.messenger.model.Timeline.GroupPosition.SINGLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * Group chats (the iOS app's GroupTests): sender labels, who a user is, members. No group exists on the dummy accounts,
 * so groups are hand-made from the real DM shapes (`state_bridge_dm.json` without a room type) and real member events.
 * Not ported: the bridge's bot and room summary, its lazy-loaded `/messages` members, and the DEBUG group.
 */
class GroupTests {
    private val me = Fixture.me
    private val theo = "@instagram_9:igm.home.arpa"
    private val maya = "@instagram_8:igm.home.arpa"
    private val minute = 60_000L

    // Sender labels (DESIGN §6.1)

    /** The name goes above the first bubble of each run, the avatar beside the last; a lone message has both. */
    @Test fun groupRunsGetTheNameFirstAndTheAvatarLast() {
        val events = listOf(message("\$1", 0, theo), message("\$2", minute, theo), message("\$3", 2 * minute, theo), message("\$4", 3 * minute, maya))
        val rows = Timeline.layout(Timeline.reduce(events, me), isGroup = true)
        assertEquals(listOf(true, false, false, true), rows.map { it.groupSender?.showsName })
        assertEquals(listOf(false, false, true, true), rows.map { it.groupSender?.showsAvatar })
    }

    /** Our own messages are never labelled or indented, in a group either. */
    @Test fun ownMessagesInAGroupHaveNoLabel() {
        val events = listOf(message("\$1", 0, theo), message("\$2", minute, me), message("\$3", 2 * minute, theo))
        val rows = Timeline.layout(Timeline.reduce(events, me), isGroup = true)
        assertNotNull(rows[0].groupSender)
        assertNull(rows[1].groupSender)
        assertEquals(Timeline.GroupSender(showsName = true, showsAvatar = true), rows[2].groupSender) // our message broke Theo's run
    }

    /** Real DMs, both directions: no labels at all. */
    @Test fun dmsHaveNoSenderLabels() {
        for (id in listOf(Fixture.tamoRoom, Fixture.kivoRoom)) {
            val room = syncRoom(id)
            val rows = Timeline.layout(Timeline.reduce(room, me), isGroup = room.bridge?.isDM == false)
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it.groupSender == null })
        }
    }

    /** Runs break where the layout already breaks them (over 3 minutes, a new day under its chip), so the name shows again. */
    @Test fun timeGapsStartANewLabelledRun() {
        val events = listOf(message("\$1", 0, theo), message("\$2", 4 * minute, theo), message("\$3", 70 * minute + 86_400_000, theo))
        val rows = Timeline.layout(Timeline.reduce(events, me), isGroup = true, zone = ZoneOffset.UTC)
        assertEquals(listOf(SINGLE, SINGLE, SINGLE), rows.map { it.position })
        assertEquals(listOf(true, true, true), rows.map { it.groupSender?.showsName })
        assertEquals(listOf(true, false, true), rows.map { it.startsDay })
    }

    /** A tombstone ("Theo unsent a message") names its sender already: no labels, still indented. */
    @Test fun unsentMessageInAGroupIsIndentedWithoutLabels() {
        val rows = Timeline.layout(Timeline.reduce(listOf(message("\$1", 0, theo), redaction("\$r", "\$1", 1, theo)), me), isGroup = true)
        assertEquals(MessageKind.Unsent, rows.first().message.kind)
        assertEquals(Timeline.GroupSender(showsName = false, showsAvatar = false), rows.first().groupSender)
    }

    // Who a user is (Room.displayName)

    @Test fun memberEventNameComesFirst() {
        val group = groupRoom(mapOf(theo to "Theo Park"))
        assertEquals("Theo Park", group.displayName(theo, UserProfile(name = "Theo P.")))
        assertEquals("Theo Park", group.knownName(theo))
    }

    /** A member event without a name (or an empty one) falls back to the profile, then the localpart; never the raw ID. */
    @Test fun nameFallsBackToTheProfileThenTheLocalpart() {
        val group = groupRoom(mapOf(theo to ""))
        group.apply(stateUpdate(state("m.room.member", mapOf("membership" to "leave"), maya)))
        assertEquals("Theo Park", group.displayName(theo, UserProfile(name = "Theo Park")))
        assertEquals("Maya Chen", group.displayName(maya, UserProfile(name = "Maya Chen")))
        assertEquals("instagram_8", group.displayName(maya))
        assertEquals("instagram_7", group.displayName("@instagram_7:igm.home.arpa", UserProfile(name = "")))
        assertNull(group.knownName(maya)) // the inbox preview then leaves the sender out
    }

    /** Real DM: someone without a member event is the contact, so the DM's name stands in; we never become the contact. */
    @Test fun inADMTheRoomNameStandsIn() {
        val room = syncRoom(Fixture.tamoRoom)
        room.ownUserID = me
        assertEquals("Kivo Studio", room.displayName("@instagram_123:igm.home.arpa"))
        assertEquals("Kivo Studio", room.displayName(Fixture.kivoGhost))
        assertEquals("me", room.displayName(me)) // our real member event
    }

    @Test fun avatarFromTheMemberEventThenTheProfile() {
        val room = syncRoom(Fixture.tamoRoom)
        assertEquals("mxc://igm.home.arpa/AKRxSivqzKQzNAwknfzLHZsH", room.avatarURL(Fixture.kivoGhost))
        assertEquals("mxc://x/y", room.avatarURL(theo, UserProfile(avatarURL = "mxc://x/y")))
        assertNull(room.avatarURL(theo))
    }

    /** The inbox preview names the sender from the profile when the member event lacks a name, never by ID. */
    @Test fun groupPreviewUsesTheProfileAndNeverAnID() {
        val group = InboxTests.portal("!g", Fixture.tamoLogin, group = true)
        group.apply(timeline(message("\$t", 20, theo, "on my way")))
        assertEquals("Theo: on my way", Inbox.preview(group, me, mapOf(theo to UserProfile(name = "Theo Park")))?.text)
        assertEquals("on my way", Inbox.preview(group, me)?.text)
    }

    /** A real older page, newest first: Kivo has an invite and several joins in it; the newest wins, not the invite. */
    @Test fun prependFillsAnUnknownMemberWithItsNewestEvent() {
        val room = groupRoom(emptyMap(), prevBatch = "t1")
        room.prepend(Fixture.messages("messages_dm_backfill"), "t1")
        val member = room.stateEvent("m.room.member", Fixture.kivoGhost)!!
        assertEquals("join", member.content["membership"].string)
        assertEquals("Kivo Studio", member.content["displayname"].string)
    }

    // Members (Chat Info)

    /** We're "You" and last, the rest sorted by name ignoring case; handles from profiles; an empty name falls back. */
    @Test fun membersSortedWithYouLast() {
        val zoe = "@instagram_7:igm.home.arpa"
        val group = groupRoom(mapOf(theo to "theo park", maya to "Maya Chen", zoe to ""))
        val members = Inbox.members(group, me, mapOf(theo to UserProfile(handle = "theo.climbs"), zoe to UserProfile(name = "Zoë Ruiz")))
        assertEquals(listOf("Maya Chen", "theo park", "Zoë Ruiz", "You"), members.map { it.name })
        assertEquals(listOf(false, false, false, true), members.map { it.isMe })
        assertEquals(listOf(null, "theo.climbs", null, null), members.map { it.handle })
    }

    @Test fun leftMembersAreNotListed() {
        val group = groupRoom(mapOf(theo to "Theo Park", maya to "Maya Chen"))
        group.apply(stateUpdate(state("m.room.member", mapOf("membership" to "leave"), maya)))
        assertEquals(listOf("Theo Park", "You"), Inbox.members(group, me, emptyMap()).map { it.name })
    }

    // Start block (DESIGN T1)

    /** Who the chat is with ("4 members", "@handle"); "You're messaging from …" only while the thread is empty. */
    @Test fun startBlockNamesWhoAndOnlyAnEmptyThreadSaysWhichAccount() {
        assertEquals(Inbox.StartBlock("4 members", null), Inbox.startBlock(isGroup = true, members = 4, handle = null, account = "Alex", isEmpty = false))
        assertEquals(Inbox.StartBlock("1 member", "You're messaging from Alex."),
            Inbox.startBlock(isGroup = true, members = 1, handle = "x", account = "Alex", isEmpty = true))
        assertNull(Inbox.startBlock(isGroup = true, members = 0, handle = null, account = "Alex", isEmpty = false).subtitle)
        assertEquals(Inbox.StartBlock("@maya.chen", "You're messaging from Alex Studio."),
            Inbox.startBlock(isGroup = false, members = 2, handle = "maya.chen", account = "Alex Studio", isEmpty = true))
        assertEquals(Inbox.StartBlock(null, null), Inbox.startBlock(isGroup = false, members = 2, handle = null, account = "Alex", isEmpty = false))
        assertNull(Inbox.startBlock(isGroup = false, members = 2, handle = null, account = null, isEmpty = true).messagingFrom)
    }

    // Seen by, the "New messages" line, two faces

    /** The newest message lists everyone else whose receipt is at or after it, first reader first; not its sender, not us. */
    @Test fun seenByNamesWhoReadTheNewestMessage() {
        val sam = "@instagram_7:igm.home.arpa"
        val zoe = "@instagram_6:igm.home.arpa"
        val events = listOf(message("\$1", 1, theo), message("\$2", 2, maya))
        val receipts = mapOf(sam to Room.Receipt("\$2", 9), theo to Room.Receipt("\$2", 5), maya to Room.Receipt("\$2", 3),
            me to Room.Receipt("\$2", 4), zoe to Room.Receipt("\$1", 1))
        assertEquals(listOf(emptyList(), listOf(theo, sam)), Timeline.reduce(events, me, receipts = receipts).map { it.seenBy })
    }

    /** Instagram's web app: two names, then a count; "everyone" once all of three or more have. */
    @Test fun seenByLine() {
        assertNull(Timeline.seenByLine(emptyList(), everyone = false))
        assertEquals("Seen by Maya", Timeline.seenByLine(listOf("Maya"), everyone = true))
        assertEquals("Seen by Maya and Sam", Timeline.seenByLine(listOf("Maya", "Sam"), everyone = true))
        assertEquals("Seen by Maya, Sam + 1", Timeline.seenByLine(listOf("Maya", "Sam", "Theo"), everyone = false))
        assertEquals("Seen by everyone", Timeline.seenByLine(listOf("Maya", "Sam", "Theo"), everyone = true))
    }

    /** The line goes above the first message from someone else after how far we'd read and after our own last message. */
    @Test fun unreadLineStartsAfterWhatWeRead() {
        val run = listOf(message("\$1", 0, theo), message("\$2", minute, theo), message("\$3", 2 * minute, theo))
        val rows = Timeline.layout(Timeline.reduce(run, me), isGroup = true, unreadAfter = 0)
        assertEquals(listOf(false, true, false), rows.map { it.startsUnread })
        assertEquals(listOf(SINGLE, FIRST, LAST), rows.map { it.position })
        assertEquals(listOf(true, true, false), rows.map { it.groupSender?.showsName })
        assertEquals(16, rows[1].spacing)

        val messages = Timeline.reduce(run + message("\$4", 3 * minute, me) + message("\$5", 4 * minute, maya), me)
        assertEquals(listOf("\$5"), Timeline.layout(messages, unreadAfter = 0).filter { it.startsUnread }.map { it.id })
        assertFalse(Timeline.layout(messages, unreadAfter = 4 * minute).any { it.startsUnread })
        assertFalse(Timeline.layout(messages).any { it.startsUnread }) // opened read
    }

    /** A group without its own picture shows two of its people, those with a picture first, never us. */
    @Test fun groupFacesPreferPictures() {
        val zoe = "@instagram_7:igm.home.arpa"
        val group = groupRoom(mapOf(theo to "Theo Park", maya to "Maya Chen", zoe to "Zoë Ruiz"))
        group.ownUserID = me
        group.apply(stateUpdate(state("m.room.member",
            mapOf("membership" to "join", "displayname" to "Zoë Ruiz", "avatar_url" to "https://scontent.example.invalid/z.jpg"), zoe)))
        assertEquals(listOf("Zoë Ruiz", "Maya Chen"), Inbox.faces(group).map { it.name })
        val pair = groupRoom(mapOf(theo to "Theo Park"))
        pair.ownUserID = me
        assertEquals(emptyList<Inbox.Member>(), Inbox.faces(pair))
    }

    /**
     * A group from the real DM `m.bridge` (room type removed) with members shaped like the real ones
     * (`{"membership": "join", "displayname": …}`) and our real member event. (iOS adds the bridge's bot too.)
     */
    private fun groupRoom(members: Map<String, String>, prevBatch: String? = null): Room {
        val bridge = Fixture.event("state_bridge_dm").let {
            it.copy(content = it.content.setting(listOf("com.beeper.room_type"), null).setting(listOf("com.beeper.room_type.v2"), null))
        }
        val joined = Fixture.sync().rooms?.join?.get(Fixture.tamoRoom)!!
        val mine = (joined.state?.events.orEmpty() + joined.timeline?.events.orEmpty())
            .filter { it.type == "m.room.member" && it.stateKey == me && it.content["membership"].string == "join" }
        val people = members.map { (id, name) -> Event("m.room.member", obj("membership" to "join", "displayname" to name), "\$m$id", id, id, 1) }
        return Room("!group").apply {
            apply(JoinedRoom(EventList(listOf(bridge) + mine + people), JoinedRoom.Timeline(emptyList(), false, prevBatch)))
        }
    }
}
