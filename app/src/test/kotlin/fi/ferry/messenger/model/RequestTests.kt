package fi.ferry.messenger.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message requests (the iOS app's RequestTests). The dummies have no request, so every request here is the real DM
 * `m.bridge` with `com.beeper.message_request: true` added (`InboxTests.portal`). In a request only Accept, Delete
 * and a reply may send anything; the engine enforces it too (core/chat rules.go). Not ported: the bridge's failure
 * statuses (the app clears `requestAction` itself when the engine refuses).
 */
class RequestTests {
    private val logins = setOf(Fixture.tamoLogin, Fixture.kivoLogin)
    private val connected = BridgeAPI.Login.Health.Connected

    @Test fun openingARequestAllowsNoReceiptsTypingOrReactions() {
        val request = InboxTests.portal("!req", Fixture.kivoLogin, request = true, unread = 2)
        assertTrue(Inbox.isRequest(request))
        assertFalse(Inbox.allowsReceipts(request)) // "Seen", typing, reactions, edits, unsends all check this
        assertEquals(Inbox.ThreadBar.REQUEST, Inbox.threadBar(request, connected, replyingInstead = false))
    }

    @Test fun requestsCountOnlyOnTheTileNeverInChatUnread() {
        val rooms = listOf(
            InboxTests.portal("!r1", Fixture.kivoLogin, request = true, unread = 3),
            InboxTests.portal("!r2", Fixture.tamoLogin, request = true),
            InboxTests.portal("!c", Fixture.kivoLogin, unread = 1),
        )
        assertEquals(2, Inbox.requestCount(rooms, Inbox.Scope.All, logins))
        assertEquals(1, Inbox.requestCount(rooms, Inbox.Scope.Account(Fixture.tamoLogin), logins))
        assertEquals(1, Inbox.unreadChats(rooms, Inbox.Scope.All, logins = logins))
        assertEquals(listOf("!r1", "!r2"), Inbox.rooms(rooms, Inbox.Scope.All, Inbox.Folder.REQUESTS, logins).map { it.id })
    }

    /** The account's tag follows the title on screen, so the words stop at "message"; TalkBack hears one sentence. */
    @Test fun requestTitleLeavesTheAccountToTheTag() {
        assertEquals(Inbox.RequestTitle("@lena.ortiz wants to message", "@lena.ortiz wants to message Alex Studio."),
            Inbox.requestTitle("@lena.ortiz", "Alex Studio"))
        assertEquals(Inbox.RequestTitle("Lena Ortiz wants to message you.", "Lena Ortiz wants to message you."),
            Inbox.requestTitle("Lena Ortiz", null))
    }

    /** Accept moves the chat at once (optimistic), but receipts wait until the engine re-sends it without the flag. */
    @Test fun acceptMovesTheChatAtOnceAndReceiptsWaitForTheEngine() {
        val room = InboxTests.portal("!req", Fixture.kivoLogin, request = true, unread = 1)
        room.requestAction = Room.RequestAction.ACCEPT
        assertFalse(Inbox.isRequest(room))
        assertEquals(Inbox.Folder.CHATS, Inbox.folder(room))
        assertEquals(0, Inbox.requestCount(listOf(room), Inbox.Scope.All, logins))
        assertEquals(1, Inbox.unreadChats(listOf(room), Inbox.Scope.All, logins = logins)) // now it counts with the chats
        assertEquals(Inbox.ThreadBar.COMPOSER, Inbox.threadBar(room, connected, replyingInstead = false))
        assertFalse("not before the engine confirms", Inbox.allowsReceipts(room))

        // Accepted on Instagram: the chat again, without the key (dropped, not set to false). That's the real DM event.
        val accepted = Fixture.event("state_bridge_dm").let { it.copy(content = it.content.setting(listOf("channel", "fi.mau.receiver"), Fixture.kivoLogin)) }
        room.apply(timeline(accepted))
        assertTrue(Inbox.allowsReceipts(room))
        assertFalse(Inbox.isRequest(room))
        assertNull("done once the flag is gone", room.requestAction)
    }

    /** A reply accepts the request first (the engine does it before sending). */
    @Test fun replyInsteadShowsTheComposerWithTheWarning() {
        val request = InboxTests.portal("!req", Fixture.kivoLogin, request = true)
        assertEquals(Inbox.ThreadBar.REPLY_ACCEPTS, Inbox.threadBar(request, connected, replyingInstead = true))
        assertFalse("typing while writing the reply still isn't sent", Inbox.allowsReceipts(request))

        val chat = InboxTests.portal("!chat", Fixture.kivoLogin)
        assertEquals(Inbox.ThreadBar.COMPOSER, Inbox.threadBar(chat, connected, replyingInstead = true))
        assertEquals(Inbox.ThreadBar.COMPOSER, Inbox.threadBar(chat, null, replyingInstead = false)) // accounts not loaded yet
    }

    /** A signed-out account can't accept or send; its recovery bar wins. */
    @Test fun signedOutAccountShowsTheRecoveryBar() {
        val request = InboxTests.portal("!req", Fixture.kivoLogin, request = true)
        val chat = InboxTests.portal("!chat", Fixture.kivoLogin)
        assertEquals(Inbox.ThreadBar.SIGNED_OUT, Inbox.threadBar(request, BridgeAPI.Login.Health.NeedsLogin, replyingInstead = true))
        assertEquals(Inbox.ThreadBar.SIGNED_OUT, Inbox.threadBar(chat, BridgeAPI.Login.Health.NeedsLogin, replyingInstead = false))
        assertEquals(Inbox.ThreadBar.COMPOSER, Inbox.threadBar(chat, BridgeAPI.Login.Health.Connecting, replyingInstead = false))
    }

    /** Delete hides the request until the engine removes the chat (`RoomRemoved`); a refusal brings it back. */
    @Test fun deleteHidesTheRequestUntilTheEngineAnswers() {
        val room = InboxTests.portal("!req", Fixture.tamoLogin, request = true, unread = 1)
        room.requestAction = Room.RequestAction.DELETE
        for (folder in listOf(Inbox.Folder.CHATS, Inbox.Folder.REQUESTS, Inbox.Folder.ARCHIVED)) {
            assertEquals(emptyList<String>(), Inbox.rooms(listOf(room), Inbox.Scope.All, folder, logins).map { it.id })
        }
        assertEquals(0, Inbox.requestCount(listOf(room), Inbox.Scope.All, logins))

        room.requestAction = null // what the app does when Instagram refuses
        assertEquals(listOf("!req"), Inbox.rooms(listOf(room), Inbox.Scope.All, Inbox.Folder.REQUESTS, logins).map { it.id })
    }

    @Test fun unknownRoomIsNeverARequestAndNeverGetsReceipts() {
        val room = Room("!unknown")
        assertFalse(Inbox.isRequest(room))
        assertFalse(Inbox.allowsReceipts(room))
    }
}
