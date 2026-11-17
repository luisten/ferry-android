package fi.ferry.messenger.model

import fi.ferry.messenger.model.BridgeAPI.Login.Health
import fi.ferry.messenger.model.Inbox.AccountStack.Status
import fi.ferry.messenger.model.Inbox.Folder.ARCHIVED
import fi.ferry.messenger.model.Inbox.Folder.CHATS
import fi.ferry.messenger.model.Inbox.Folder.GENERAL
import fi.ferry.messenger.model.Inbox.Folder.REQUESTS
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The inbox's rules (the iOS app's InboxTests and AccountStackTests). Accounts come from the engine's logins (the iOS
 * tests read the bridge's whoami); the requests stand in for the iOS app's DEBUG request rooms. Not ported yet: the
 * row's time label and the stored Requests markers (A2, they need Android's locale settings and storage), the DEBUG
 * demo data, account identities.
 */
class InboxTests {
    private val both = setOf(Fixture.tamoLogin, Fixture.kivoLogin)
    private val all = Inbox.Scope.All
    private val tamo = Inbox.Scope.Account(Fixture.tamoLogin)
    private val kivo = Inbox.Scope.Account(Fixture.kivoLogin)

    // Inclusion and accounts (the bridge-era sync)

    @Test fun eachChatMapsToItsAccount() {
        val rooms = realRooms()
        assertEquals(listOf(Fixture.tamoRoom), Inbox.rooms(rooms, tamo, CHATS, both).map { it.id })
        assertEquals(listOf(Fixture.kivoRoom), Inbox.rooms(rooms, kivo, CHATS, both).map { it.id })
        assertEquals(emptyList<String>(), Inbox.rooms(rooms, Inbox.Scope.Account("someone-else"), CHATS, both).map { it.id })
    }

    @Test fun beforeTheAccountsLoadEveryChatShows() {
        assertEquals(setOf(Fixture.tamoRoom, Fixture.kivoRoom), Inbox.rooms(realRooms(), all, CHATS, null).map { it.id }.toSet())
    }

    @Test fun chatsOfRemovedAccountsAreHidden() {
        assertEquals(listOf(Fixture.tamoRoom), Inbox.rooms(realRooms(), all, CHATS, setOf(Fixture.tamoLogin)).map { it.id })
    }

    /** A room the engine didn't make (no `m.bridge`) never shows. */
    @Test fun aRoomWithoutABridgeIsHidden() {
        val bot = Room("!bot")
        bot.apply(timeline(state("m.room.create", mapOf("room_version" to "12")), message("\$help", 5, Fixture.bot, "Use !ig help")))
        assertEquals(emptyList<String>(), Inbox.rooms(listOf(bot), all, CHATS, null).map { it.id })
    }

    // Folders

    @Test fun requestShowsOnlyInRequests() {
        val rooms = listOf(portal("!req", Fixture.tamoLogin, request = true), portal("!chat", Fixture.tamoLogin))
        assertEquals(listOf("!chat"), Inbox.rooms(rooms, all, CHATS, both).map { it.id })
        assertEquals(listOf("!req"), Inbox.rooms(rooms, all, REQUESTS, both).map { it.id })
        assertEquals(emptyList<String>(), Inbox.rooms(rooms, all, ARCHIVED, both).map { it.id })
    }

    @Test fun archivedShowsOnlyInArchived() {
        val archived = portal("!arch", Fixture.kivoLogin, ts = 1000, tags = mapOf(Inbox.ARCHIVED_TAG to emptyObject))
        assertEquals(emptyList<String>(), Inbox.rooms(listOf(archived), all, CHATS, both).map { it.id })
        assertEquals(listOf("!arch"), Inbox.rooms(listOf(archived), all, ARCHIVED, both).map { it.id })
    }

    /** An archived chat comes back on new activity: the tag says when it was set, so this needs no write. */
    @Test fun archivedChatReturnsOnNewActivity() {
        val tag = mapOf(Inbox.ARCHIVED_TAG to obj(Inbox.ARCHIVED_AT_KEY to 2000))
        assertEquals(ARCHIVED, Inbox.folder(portal("!quiet", Fixture.kivoLogin, ts = 1500, tags = tag)))
        assertEquals(CHATS, Inbox.folder(portal("!active", Fixture.kivoLogin, ts = 2500, tags = tag)))
    }

    @Test fun archivedRequestStaysARequest() {
        assertEquals(REQUESTS, Inbox.folder(portal("!r", Fixture.kivoLogin, request = true, tags = mapOf(Inbox.ARCHIVED_TAG to emptyObject))))
    }

    // Primary and General

    /** Instagram's moves (`SlideUQPPIGMoveThread`) become `fi.mau.instagram.inbox_folder`; the chat follows out and back. */
    @Test fun realMovesTakeTheChatToGeneralAndBack() {
        val folders = json.decodeFromString<List<JsonElement>>(Fixture.text("deltas_s5_ig_move_thread")).map { it["ig_inbox_folder"].string!! }
        assertEquals(listOf("GENERAL", "PRIMARY"), folders)
        val room = portal("!k", Fixture.kivoLogin)
        assertEquals(BridgeInfo.InboxFolder.PRIMARY, room.bridge?.folder) // never moved: no key
        assertEquals(CHATS, Inbox.folder(room))

        room.apply(stateUpdate(bridge(Fixture.kivoLogin, folders[0])))
        assertEquals(BridgeInfo.InboxFolder.GENERAL, room.bridge?.folder)
        assertEquals(emptyList<String>(), Inbox.rooms(listOf(room), all, CHATS, both).map { it.id })
        assertEquals(listOf("!k"), Inbox.rooms(listOf(room), all, GENERAL, both).map { it.id })
        assertEquals(listOf("!k"), Inbox.rooms(listOf(room), kivo, GENERAL, both).map { it.id })

        room.apply(stateUpdate(bridge(Fixture.kivoLogin, folders[1])))
        assertEquals(BridgeInfo.InboxFolder.PRIMARY, room.bridge?.folder)
        assertEquals(CHATS, Inbox.folder(room))
    }

    /** Only GENERAL leaves the main list: a value this app doesn't know stays where the chat is today. */
    @Test fun unknownFolderValueIsPrimary() {
        val dm = Fixture.event("state_bridge_dm").content
        fun folder(value: String) = BridgeInfo.parse(dm.setting(listOf("channel", BridgeInfo.INBOX_FOLDER_KEY), value))?.folder
        assertEquals(BridgeInfo.InboxFolder.PRIMARY, folder("OTHER"))
        assertEquals(BridgeInfo.InboxFolder.PRIMARY, folder("PRIMARY"))
        assertEquals(BridgeInfo.InboxFolder.GENERAL, folder("GENERAL"))
    }

    /** The General tile shows in any scope that holds a professional account (All included), never for a personal one alone. */
    @Test fun generalTileShowsForScopesWithAProfessionalAccount() {
        val rooms = realRooms()
        assertTrue(Inbox.showsGeneral(rooms, kivo, accounts(), both))
        assertTrue(Inbox.showsGeneral(rooms, all, accounts(), both))
        assertFalse(Inbox.showsGeneral(rooms, tamo, accounts(), both))
        assertFalse(Inbox.showsGeneral(rooms, all, accountsWithoutType(), both)) // type unknown, no General chat: no tile
    }

    /** "Data wins": a General chat shows its tile even when the account's type isn't known (yet). */
    @Test fun aGeneralChatShowsTheTileWithoutTheFlag() {
        val general = portal("!g", Fixture.tamoLogin, inboxFolder = "GENERAL")
        assertTrue(Inbox.showsGeneral(listOf(general), tamo, accountsWithoutType(), both))
        assertFalse(Inbox.showsGeneral(listOf(general), kivo, accountsWithoutType(), both))
    }

    /** The main list's unread counts Primary only; General's unread shows on its tile. */
    @Test fun generalUnreadCountsOnlyOnItsTile() {
        val rooms = listOf(
            portal("!p", Fixture.kivoLogin, unread = 1),
            portal("!g1", Fixture.kivoLogin, unread = 2, inboxFolder = "GENERAL"),
            portal("!g2", Fixture.kivoLogin, inboxFolder = "GENERAL"),
        )
        assertEquals(1, Inbox.unreadChats(rooms, kivo, logins = both))
        assertEquals(1, Inbox.unreadChats(rooms, all, GENERAL, both))
        assertEquals(2, Inbox.rooms(rooms, all, GENERAL, both).size)
    }

    /** Archive and requests come first; an archived General chat comes back to General on new activity. */
    @Test fun archiveAndRequestsWinOverTheTab() {
        val tag = mapOf(Inbox.ARCHIVED_TAG to obj(Inbox.ARCHIVED_AT_KEY to 2000))
        assertEquals(ARCHIVED, Inbox.folder(portal("!q", Fixture.kivoLogin, ts = 1500, tags = tag, inboxFolder = "GENERAL")))
        assertEquals(GENERAL, Inbox.folder(portal("!a", Fixture.kivoLogin, ts = 2500, tags = tag, inboxFolder = "GENERAL")))
        assertEquals(REQUESTS, Inbox.folder(portal("!r", Fixture.kivoLogin, request = true, inboxFolder = "GENERAL")))
    }

    @Test fun searchFindsGeneralChats() {
        val general = named("!g", "Ana", Fixture.kivoLogin, inboxFolder = "GENERAL")
        assertEquals(listOf("!g"), Inbox.search(listOf(general), "ana", all, both).map { it.id })
    }

    // Archive

    @Test fun archiveTagRecordsWhenAndArchivesTheChat() {
        val room = portal("!a", Fixture.kivoLogin, ts = 1_000_000)
        val tag = Inbox.archiveTag(room, now = 2_000_000)
        assertEquals(obj(Inbox.ARCHIVED_AT_KEY to 2_000_000), tag)
        room.tags = mapOf(Inbox.ARCHIVED_TAG to tag)
        assertEquals(ARCHIVED, Inbox.folder(room))
        assertEquals(listOf("!a"), Inbox.rooms(listOf(room), all, ARCHIVED, both).map { it.id })
        room.tags = emptyMap() // unarchive
        assertEquals(CHATS, Inbox.folder(room))
    }

    /** A phone clock behind Instagram's mustn't date the archive before the last message, or the chat would come back. */
    @Test fun archiveTimeIsNeverBeforeTheLastMessage() {
        val room = portal("!a", Fixture.kivoLogin, ts = 5_000_000)
        val tag = Inbox.archiveTag(room, now = 1_000_000) // 4000 s behind
        assertEquals(5_000_000L, tag[Inbox.ARCHIVED_AT_KEY].long)
        room.tags = mapOf(Inbox.ARCHIVED_TAG to tag)
        assertEquals(ARCHIVED, Inbox.folder(room))
        room.apply(timeline(message("\$next", 5_000_001)))
        assertEquals(CHATS, Inbox.folder(room)) // and the next message does bring it back
    }

    // Search

    @Test fun searchMatchesNameIgnoringCaseAndAccentsWithinScope() {
        val rooms = listOf(
            named("!k", "Kivo Studio", Fixture.tamoLogin, ts = 3),
            named("!l", "Joana Simões", Fixture.tamoLogin, ts = 2),
            named("!o", "Kim Lee", Fixture.kivoLogin, ts = 1),
        )
        assertEquals(listOf("!k"), Inbox.search(rooms, "kiv", all, both).map { it.id })
        assertEquals(listOf("!l"), Inbox.search(rooms, "SIMOES", all, both).map { it.id })
        assertEquals(listOf("!k", "!o"), Inbox.search(rooms, "k", all, both).map { it.id }) // newest first
        assertEquals(listOf("!o"), Inbox.search(rooms, "k", kivo, both).map { it.id })
        assertEquals(emptyList<String>(), Inbox.search(rooms, "  ", all, both).map { it.id })
    }

    /** Archived chats are found (search is a way back to them); requests aren't. */
    @Test fun searchFindsArchivedChatsButNotRequests() {
        val archived = named("!a", "Ana", Fixture.tamoLogin, tags = mapOf(Inbox.ARCHIVED_TAG to emptyObject))
        val request = named("!r", "Anabel", Fixture.tamoLogin, request = true)
        assertEquals(listOf("!a"), Inbox.search(listOf(archived, request), "ana", all, both).map { it.id })
    }

    // Chat Info

    /** Only a DM with another of our own accounts has a known @handle: its channel ID is that account. */
    @Test fun contactHandleIsKnownOnlyForOurOtherAccounts() {
        assertEquals("tamoveska", Inbox.contactHandle(syncRoom(Fixture.kivoRoom), accounts()))
        assertEquals("kivostudio", Inbox.contactHandle(syncRoom(Fixture.tamoRoom), accounts()))
        assertNull(Inbox.contactHandle(syncRoom(Fixture.kivoRoom), emptyList()))
        assertNull(Inbox.contactHandle(portal("!g", Fixture.tamoLogin, group = true), accounts()))
    }

    // Order and counts

    @Test fun sortedByLastActivityNewestFirst() {
        val rooms = realRooms()
        rooms.first { it.id == Fixture.kivoRoom }.apply(timeline(message("\$new", 1_800_000_000_000)))
        assertEquals(listOf(Fixture.kivoRoom, Fixture.tamoRoom), Inbox.rooms(rooms, all, CHATS, both).map { it.id })
    }

    /** Both real chats' last message is the same Instagram message (the same time): the order must not flicker. */
    @Test fun equalActivityHasAStableOrder() {
        assertEquals(Inbox.rooms(realRooms(), all, CHATS, both).map { it.id }, Inbox.rooms(realRooms().reversed(), all, CHATS, both).map { it.id })
    }

    @Test fun unreadCountsExcludeRequestsAndArchived() {
        val rooms = listOf(
            portal("!t1", Fixture.tamoLogin, unread = 3),
            portal("!t2", Fixture.tamoLogin, unread = 1),
            portal("!t3", Fixture.tamoLogin),
            portal("!k1", Fixture.kivoLogin, unread = 1),
            portal("!kreq", Fixture.kivoLogin, request = true, unread = 1),
            portal("!karch", Fixture.kivoLogin, unread = 1, tags = mapOf(Inbox.ARCHIVED_TAG to emptyObject)),
        )
        assertEquals(3, Inbox.unreadChats(rooms, all, logins = both)) // chats, not messages
        assertEquals(2, Inbox.unreadChats(rooms, tamo, logins = both))
        assertEquals(1, Inbox.unreadChats(rooms, kivo, logins = both))
    }

    @Test fun requestCountPerScope() {
        assertEquals(0, Inbox.requestCount(realRooms(), all, both))
        val rooms = listOf(
            portal("!a", Fixture.tamoLogin, request = true),
            portal("!b", Fixture.kivoLogin, request = true, unread = 1),
            portal("!c", Fixture.kivoLogin),
        )
        assertEquals(2, Inbox.requestCount(rooms, all, both))
        assertEquals(1, Inbox.requestCount(rooms, kivo, both))
    }

    // Folders menu and Requests card (D14)

    /** Never opened: every request is new, in every scope that shows it. */
    @Test fun everyRequestIsNewBeforeRequestsIsOpened() {
        val rooms = requests()
        assertEquals(listOf("Kestrel Bikes", "Lena Ortiz", "crypto.wins.daily"), Inbox.newRequests(rooms, all, both, emptyMap()).map { it.name })
        assertEquals(listOf("Lena Ortiz"), Inbox.newRequests(rooms, kivo, both, emptyMap()).map { it.name })
    }

    /** The card goes once Requests was opened in a scope that showed the request; a new message brings it back. */
    @Test fun requestsAreNewOnlyAfterTheMarkerOfTheirScope() {
        val rooms = requests()
        val seenAll = mapOf("" to NOW - 4 * HOUR) // opened in All 4 h ago: only Kestrel (2 h ago) is newer
        assertEquals(listOf("Kestrel Bikes"), Inbox.newRequests(rooms, all, both, seenAll).map { it.name })
        assertEquals(listOf("Kestrel Bikes"), Inbox.newRequests(rooms, tamo, both, seenAll).map { it.name })
        assertEquals(emptyList<String>(), Inbox.newRequests(rooms, kivo, both, seenAll).map { it.name })
        // Opened in Kivo's scope just now: Kivo's request is no longer new in All either; Tamo's still are.
        val seenKivo = mapOf(Fixture.kivoLogin to NOW)
        assertEquals(listOf("Kestrel Bikes", "crypto.wins.daily"), Inbox.newRequests(rooms, all, both, seenKivo).map { it.name })
        assertEquals(emptyList<String>(), Inbox.newRequests(rooms, kivo, both, seenKivo).map { it.name })
        assertEquals(emptyList<String>(), Inbox.newRequests(rooms, all, both, mapOf("" to NOW)).map { it.name })
        // Lena writes again: her request is new again.
        rooms.first { it.name == "Lena Ortiz" }.apply(timeline(message("\$again", NOW + 60_000, "@ig_17841400000000002")))
        assertEquals(listOf("Lena Ortiz"), Inbox.newRequests(rooms, all, both, mapOf("" to NOW)).map { it.name })
    }

    /** The marker is now, or the newest request in scope when the phone's clock is behind Instagram's. */
    @Test fun seenMarkerIsNeverBeforeTheNewestRequestInScope() {
        val rooms = requests()
        val kestrel = NOW - 120 * 60_000 + 40_000 // its second message
        assertEquals(NOW, Inbox.requestsSeenMarker(rooms, all, both, NOW))
        val behind = NOW - 3 * HOUR
        assertEquals(kestrel, Inbox.requestsSeenMarker(rooms, all, both, behind))
        assertEquals(behind, Inbox.requestsSeenMarker(rooms, kivo, both, behind)) // Lena (5 h ago) is older than the clock
        assertEquals(emptyList<String>(), Inbox.newRequests(rooms, all, both, mapOf("" to Inbox.requestsSeenMarker(rooms, all, both, behind))).map { it.name })
    }

    /** Badge = new requests in scope + unread General chats in scope; 0 (hidden) on a quiet day. */
    @Test fun badgeCountsNewRequestsAndUnreadGeneralChats() {
        assertEquals(0, Inbox.foldersMenu(realRooms(), all, accounts(), both, emptyMap()).badge)

        val general = portal("!k", Fixture.kivoLogin, unread = 2)
        general.apply(stateUpdate(bridge(Fixture.kivoLogin, "GENERAL")))
        val rooms = requests() + general
        fun menu(scope: Inbox.Scope, seen: Map<String, Long>) = Inbox.foldersMenu(rooms, scope, accounts(), both, seen)
        assertEquals(4, menu(all, emptyMap()).badge) // 3 new requests + 1 unread General chat (chats, not messages)
        assertEquals(2, menu(kivo, emptyMap()).badge)
        assertEquals(2, menu(tamo, emptyMap()).badge)
        assertEquals(1, menu(all, mapOf("" to NOW)).badge) // requests looked at; General's unread stays
        assertEquals(0, menu(tamo, mapOf("" to NOW)).badge)

        // Moved back to Primary: a main-list chat again.
        general.apply(stateUpdate(Fixture.event("state_bridge_dm_after_move")))
        assertEquals(0, menu(kivo, mapOf(Fixture.kivoLogin to NOW)).badge)
    }

    /** General stays quiet: an unread General chat feeds the badge and the menu, never the Requests card. */
    @Test fun unreadGeneralChatNeverShowsTheCard() {
        val general = portal("!g", Fixture.kivoLogin, unread = 1, inboxFolder = "GENERAL")
        assertEquals(emptyList<String>(), Inbox.newRequests(listOf(general), all, both, emptyMap()).map { it.name })
        assertEquals(1, Inbox.foldersMenu(listOf(general), all, accounts(), both, emptyMap()).badge)
    }

    /** General is an item only where a professional account is in scope (or a General chat: data wins). */
    @Test fun generalItemOnlyInScopesWithAProfessionalAccount() {
        val rooms = realRooms()
        fun items(scope: Inbox.Scope, accounts: List<BridgeAPI.Login>) = Inbox.foldersMenu(rooms, scope, accounts, both, emptyMap()).items
        assertEquals(listOf(GENERAL, REQUESTS, ARCHIVED), items(kivo, accounts()))
        assertEquals(listOf(GENERAL, REQUESTS, ARCHIVED), items(all, accounts()))
        assertEquals(listOf(REQUESTS, ARCHIVED), items(tamo, accounts()))
        assertEquals(listOf(REQUESTS, ARCHIVED), items(all, accountsWithoutType()))
    }

    /** Subtitles only when non-zero: "2 waiting" counts every request in scope, "1 unread" General's unread chats. */
    @Test fun menuSubtitlesOnlyWhenNonZero() {
        val general = portal("!g", Fixture.kivoLogin, unread = 1, inboxFolder = "GENERAL")
        val quiet = Inbox.foldersMenu(realRooms(), all, accounts(), both, emptyMap())
        assertEquals(listOf(null, null, null), quiet.items.map(quiet::subtitle))
        val busy = Inbox.foldersMenu(requests() + general, all, accounts(), both, mapOf("" to NOW)) // looked at, still waiting
        assertEquals(listOf("1 unread", "3 waiting", null), busy.items.map(busy::subtitle))
        val tamoMenu = Inbox.foldersMenu(requests(), tamo, accounts(), both, emptyMap())
        assertEquals(listOf("2 waiting", null), tamoMenu.items.map(tamoMenu::subtitle))
    }

    /** TalkBack: the button says what its badge counts; the card names who's waiting. */
    @Test fun foldersAndCardAccessibilityLabels() {
        val general = portal("!g", Fixture.kivoLogin, unread = 1, inboxFolder = "GENERAL")
        fun label(rooms: List<Room>, seen: Map<String, Long>) = Inbox.foldersMenu(rooms, all, accounts(), both, seen).accessibilityLabel
        assertEquals("Folders", label(realRooms(), emptyMap()))
        assertEquals("Folders, 3 new requests", label(requests(), emptyMap()))
        assertEquals("Folders, 1 new request, 1 unread in General", label(requests() + general, mapOf(Fixture.tamoLogin to NOW)))

        assertEquals("1 message request", Inbox.requestsCardTitle(1))
        assertEquals("2 message requests", Inbox.requestsCardTitle(2))
        assertEquals("2 message requests, from Lena Ortiz and crypto.wins.daily. Opens Requests.",
            Inbox.requestsCardLabel(listOf("Lena Ortiz", "crypto.wins.daily")))
        assertEquals("3 message requests, from Kestrel Bikes, Lena Ortiz and crypto.wins.daily. Opens Requests.",
            Inbox.requestsCardLabel(listOf("Kestrel Bikes", "Lena Ortiz", "crypto.wins.daily")))
    }

    // The request rule

    @Test fun receiptsAndTypingNeverAllowedInRequests() {
        assertFalse(Inbox.allowsReceipts(portal("!req", Fixture.tamoLogin, request = true)))
        assertTrue(Inbox.allowsReceipts(syncRoom(Fixture.tamoRoom)))
        assertFalse(Inbox.allowsReceipts(Room("!unknown"))) // nothing known yet: don't risk a "Seen"
    }

    // Row preview

    @Test fun previewFromRealChats() {
        // The last message in both real chats is a reel.
        assertEquals(Inbox.Preview(Glyph.REEL, "Sent a reel"), Inbox.preview(syncRoom(Fixture.tamoRoom), Fixture.me))
        assertEquals("You sent a reel", Inbox.preview(syncRoom(Fixture.kivoRoom), Fixture.me)?.text)
    }

    @Test fun previewText() {
        val room = portal("!p", Fixture.tamoLogin)
        room.apply(timeline(message("\$1", 9, Fixture.me, "see you\nthere")))
        assertEquals(Inbox.Preview(null, "You: see you there"), Inbox.preview(room, Fixture.me))
        room.apply(timeline(message("\$2", 10, msgtype = "m.image")))
        assertEquals(Inbox.Preview(Glyph.PHOTO, "Photo"), Inbox.preview(room, Fixture.me))
        assertNull(Inbox.preview(Room("!empty"), Fixture.me))
    }

    /** Marked unread on this device: unread for the dot and the counts while Instagram has it read. */
    @Test fun markedUnreadCountsAsUnread() {
        val room = portal("!p", Fixture.tamoLogin)
        assertFalse(room.isUnread)
        room.tags = mapOf(Inbox.MARKED_UNREAD_TAG to emptyObject)
        assertTrue(room.isUnread)
        assertTrue(room.isMarkedUnread)
    }

    /**
     * A reaction to our message (`core/chat`'s reaction log entry): no row in the thread, but the chat's newest message,
     * so it moves the chat up and is the preview, as in Instagram (option B, 2026-11-10).
     */
    @Test fun reactionLogIsThePreviewButNoRow() {
        val room = portal("!p", Fixture.tamoLogin)
        room.apply(timeline(
            message("\$1", 9, Fixture.me, "see you there"),
            message("\$log", 20, Fixture.kivoGhost, "Reacted 😂 to your message", msgtype = "m.notice",
                extra = mapOf(MessageKind.REACTION_LOG_KEY to true)),
        ))
        assertEquals("Reacted 😂 to your message", Inbox.preview(room, Fixture.me)?.text)
        assertEquals(20L, room.lastActivity)
        assertEquals("\$1", Timeline.reduce(room, Fixture.me).last().id)
    }

    @Test fun replyPreviewStripsTheFallback() {
        val room = portal("!p", Fixture.tamoLogin)
        room.apply(timeline(message("\$r", 9, Fixture.kivoGhost, "> <@x> hi\n\nhello back",
            extra = mapOf("m.relates_to" to mapOf("m.in_reply_to" to mapOf("event_id" to "\$m!p"))))))
        assertEquals(Inbox.Preview(null, "hello back"), Inbox.preview(room, Fixture.me))
    }

    @Test fun groupPreviewNamesTheSender() {
        val group = portal("!g", Fixture.tamoLogin, group = true)
        group.apply(timeline(
            state("m.room.member", mapOf("membership" to "join", "displayname" to "Theo Park"), "@instagram_9:igm.home.arpa"),
            message("\$v", 20, "@instagram_9:igm.home.arpa", msgtype = "m.audio", extra = mapOf("org.matrix.msc3245.voice" to emptyMap<String, Any>())),
        ))
        assertEquals(Inbox.Preview(Glyph.WAVEFORM, "Theo: Voice message"), Inbox.preview(group, Fixture.me))
    }

    // Health and banners

    @Test fun healthFromTheAccountsState() {
        assertEquals(Health.NeedsLogin, login("BAD_CREDENTIALS").health)
        assertEquals(Health.NeedsLogin, login("LOGGED_OUT").health)
        assertEquals(Health.Syncing, login("BACKFILLING").health)
        assertEquals(Health.Connecting, login("TRANSIENT_DISCONNECT").health) // just seen: not news yet
        assertEquals(Health.Error("boom"), login("UNKNOWN_ERROR", message = "boom").health)
        assertEquals(Health.Connecting, login("CONNECTING").health)
        assertEquals(Health.Error(null), login("SOMETHING_NEW").health)
    }

    @Test fun bannerPriority() {
        val ok = login("CONNECTED")
        val out = login("BAD_CREDENTIALS", "1")
        val out2 = login("LOGGED_OUT", "2")
        assertNull(Inbox.banner(1, listOf(ok))) // one blip isn't news
        assertEquals(Inbox.Banner.Offline, Inbox.banner(2, listOf(out))) // the network first
        assertEquals(Inbox.Banner.Account("1"), Inbox.banner(0, listOf(ok, out)))
        assertEquals(Inbox.Banner.Accounts(2), Inbox.banner(0, listOf(out, out2)))
        assertNull(Inbox.banner(0, listOf(login("CONNECTING"))))
        assertNull(Inbox.banner(0, listOf(login("BACKFILLING"))))
        assertEquals(Inbox.Banner.Account("3"), Inbox.banner(0, listOf(login("UNKNOWN_ERROR", "3"))))
    }

    /** A TRANSIENT_DISCONNECT is a quiet spinner, and a banner only once it has lasted over 2 minutes. */
    @Test fun transientDisconnectRaisesABannerAfterTwoMinutes() {
        val early = login("TRANSIENT_DISCONNECT", "7").copy(transientSince = NOW - 119_000)
        assertEquals(BridgeAPI.Login.Health.Connecting, early.health(NOW))
        assertNull(Inbox.banner(0, listOf(early), NOW))
        val late = early.copy(transientSince = NOW - 121_000)
        assertEquals(BridgeAPI.Login.Health.Reconnecting, late.health(NOW))
        assertEquals(Inbox.Banner.Account("7"), Inbox.banner(0, listOf(late), NOW))
    }

    /** DESIGN A3: "Personal and Band keep working." */
    @Test fun reloginNamesTheAccountsThatKeepWorking() {
        assertNull(Inbox.othersKeepWorking(emptyList()))
        assertEquals("Personal keeps working.", Inbox.othersKeepWorking(listOf("Personal")))
        assertEquals("Personal and Band keep working.", Inbox.othersKeepWorking(listOf("Personal", "Band")))
        assertEquals("A, B and C keep working.", Inbox.othersKeepWorking(listOf("A", "B", "C")))
    }

    // Identity defaults and the row's time

    @Test fun defaultIdentitiesFollowThePaletteOrder() {
        val ids = AccountIdentity.withDefaults(emptyMap(), accounts())
        assertEquals(AccountIdentity("Tamo Veska", AccountColor.OCEAN, "T", 0), ids[Fixture.tamoLogin])
        assertEquals(AccountIdentity("Kivo Studio", AccountColor.EMBER, "K", 1), ids[Fixture.kivoLogin])
    }

    @Test fun defaultsKeepExistingAndAvoidATakenColorAndMark() {
        val mine = AccountIdentity("Studio", AccountColor.OCEAN, "K", 5)
        val ids = AccountIdentity.withDefaults(mapOf(Fixture.tamoLogin to mine), accounts())
        assertEquals(mine, ids[Fixture.tamoLogin])
        // "K" is taken, so the next distinct letter of "Kivo Studio"; Ocean is taken, so Ember.
        assertEquals(AccountIdentity("Kivo Studio", AccountColor.EMBER, "I", 6), ids[Fixture.kivoLogin])
    }

    @Test fun aDuplicateDisplayNameFallsBackToTheUsername() {
        val logins = accounts().let { listOf(it[0], it[1].copy(profile = it[1].profile?.copy(name = "Tamo Veska"))) }
        assertEquals("kivostudio", AccountIdentity.withDefaults(emptyMap(), logins)[Fixture.kivoLogin]?.name)
    }

    @Test fun timeLabel() {
        val utc = java.time.ZoneOffset.UTC // NOW is 2026-10-13 14:00 UTC
        val day = 86_400_000L
        assertEquals("13:59", Inbox.timeLabel(NOW - 60_000, NOW, utc))
        assertEquals("Yesterday", Inbox.timeLabel(NOW - day, NOW, utc))
        assertEquals("Sat", Inbox.timeLabel(NOW - 3 * day, NOW, utc, Inbox.TimeFormats(locale = java.util.Locale.US)))
        assertEquals("1 Aug", Inbox.timeLabel(1_785_585_600_000, NOW, utc)) // 2026-08-01
        assertEquals("1 Aug 2025", Inbox.timeLabel(1_754_049_600_000, NOW, utc))
    }

    // Account stack (DESIGN §3.5): side by side, up to three, then the first two and "+N"

    private fun account(name: String, health: BridgeAPI.Login.Health = BridgeAPI.Login.Health.Connected) =
        Inbox.AccountStack.Account(name.lowercase(), name, health)

    @Test fun upToThreeAccountsAllShow() {
        for (count in 1..3) {
            val names = listOf("Alex", "Alex Studio", "Band").take(count)
            val stack = Inbox.AccountStack(names.map { account(it) })
            assertEquals(names, stack.shown.map { it.name })
            assertTrue(stack.hidden.isEmpty())
        }
    }

    @Test fun fromFourAccountsTheFirstTwoAndACount() {
        val names = listOf("Alex", "Alex Studio", "Band", "Market", "Shop", "Club")
        for (count in 4..6) {
            val stack = Inbox.AccountStack(names.take(count).map { account(it) })
            assertEquals(listOf("Alex", "Alex Studio"), stack.shown.map { it.name })
            assertEquals(count - 2, stack.hidden.size)
        }
    }

    @Test fun noAccountsShowNothing() {
        val stack = Inbox.AccountStack(emptyList())
        assertTrue(stack.shown.isEmpty())
        assertTrue(stack.hidden.isEmpty())
        assertNull(stack.hiddenStatus)
        assertEquals("Accounts", stack.accessibilityLabel)
    }

    /** Signed out and errors need you; connecting, syncing and reconnecting spin. */
    @Test fun statusFromHealth() {
        assertNull(account("A", Health.Connected).status)
        assertEquals(Status.NEEDS_YOU, account("A", Health.NeedsLogin).status)
        assertEquals(Status.NEEDS_YOU, account("A", Health.Error("boom")).status)
        assertEquals(Status.NEEDS_YOU, account("A", Health.Error(null)).status)
        assertEquals(Status.CONNECTING, account("A", Health.Connecting).status)
        assertEquals(Status.CONNECTING, account("A", Health.Syncing).status)
        assertEquals(Status.CONNECTING, account("A", Health.Reconnecting).status)
    }

    @Test fun aHiddenAccountsStatusGoesOnTheCountAndNeedsYouWins() {
        val shown = Inbox.AccountStack(listOf(account("Alex", BridgeAPI.Login.Health.Connecting), account("Alex Studio", BridgeAPI.Login.Health.NeedsLogin)))
        assertEquals(listOf(Inbox.AccountStack.Status.CONNECTING, Inbox.AccountStack.Status.NEEDS_YOU), shown.shown.map { it.status })
        assertNull(shown.hiddenStatus)
        val accounts = listOf(account("Alex"), account("Alex Studio"), account("Band", BridgeAPI.Login.Health.Connecting), account("Market"))
        assertEquals(Inbox.AccountStack.Status.CONNECTING, Inbox.AccountStack(accounts).hiddenStatus)
        val worse = accounts + account("Shop", BridgeAPI.Login.Health.NeedsLogin)
        assertEquals(Inbox.AccountStack.Status.NEEDS_YOU, Inbox.AccountStack(worse).hiddenStatus)
        assertEquals(listOf(null, null), Inbox.AccountStack(worse).shown.map { it.status })
    }

    @Test fun accessibilityLabelNamesTheShownAccountsAndWhoNeedsAttention() {
        assertEquals("Accounts: Alex.", Inbox.AccountStack(listOf(account("Alex"))).accessibilityLabel)
        assertEquals("Accounts: Alex and Alex Studio.", Inbox.AccountStack(listOf(account("Alex"), account("Alex Studio"))).accessibilityLabel)
        assertEquals("Accounts: Alex, Alex Studio, and Band.",
            Inbox.AccountStack(listOf("Alex", "Alex Studio", "Band").map { account(it) }).accessibilityLabel)
        assertEquals("Accounts: Alex, Alex Studio, and 2 more.",
            Inbox.AccountStack(listOf("Alex", "Alex Studio", "Band", "Market").map { account(it) }).accessibilityLabel)
        assertEquals("Accounts: Alex, Alex Studio, and 2 more. Alex Studio and Market need attention. Alex is connecting.",
            Inbox.AccountStack(listOf(account("Alex", Health.Connecting), account("Alex Studio", Health.NeedsLogin), account("Band"), account("Market", Health.Error(null))))
                .accessibilityLabel)
        assertEquals("Accounts: Alex and Band. Alex and Band are connecting.",
            Inbox.AccountStack(listOf(account("Alex", Health.Syncing), account("Band", Health.Reconnecting))).accessibilityLabel)
    }

    @Test fun scopeSubtitleDropsTheHandleWhenTheStackIsWidest() {
        assertEquals("@alexstudio · Primary", Inbox.scopeSubtitle("alexstudio", isProfessional = true, accounts = 2))
        assertEquals("Primary", Inbox.scopeSubtitle("alexstudio", isProfessional = true, accounts = 3))
        assertEquals("@alex.personal", Inbox.scopeSubtitle("alex.personal", isProfessional = false, accounts = 4))
        assertEquals("", Inbox.scopeSubtitle(null, isProfessional = true, accounts = 2))
    }

    companion object {
        private const val NOW = 1_791_900_000_000L
        private const val HOUR = 3_600_000L

        /** The dummies' chats from the bridge-era sync (without its two personal spaces, which the engine never makes). */
        fun realRooms(): List<Room> = listOf(Fixture.kivoRoom, Fixture.tamoRoom).map(::syncRoom)

        /** The dummies as the engine logs them in: tamoveska personal, kivostudio professional. */
        fun accounts(): List<BridgeAPI.Login> = listOf("tamo", "kivo").map {
            val login = Fixture.engine<Map<String, JsonElement>>("login_$it").getValue("login")
            val decoded = json.decodeFromJsonElement(IG.Login.serializer(), login)
            IGAdapter.login(decoded.account, decoded.viewer, "CONNECTED", since = NOW)
        }

        /** The same without the account type (not known yet). */
        fun accountsWithoutType(): List<BridgeAPI.Login> = accounts().map { it.copy(state = it.state?.copy(info = null)) }

        fun login(state: String, id: String = "0", message: String? = null) =
            BridgeAPI.Login(id, state = BridgeAPI.Login.State(state, message))

        /** The real DM `m.bridge` for another account, optionally moved to an Instagram folder. */
        fun bridge(receiver: String, inboxFolder: String? = null): Event {
            val dm = Fixture.event("state_bridge_dm")
            var content = dm.content.setting(listOf("channel", "fi.mau.receiver"), receiver)
            if (inboxFolder != null) content = content.setting(listOf("channel", BridgeInfo.INBOX_FOLDER_KEY), inboxFolder)
            return dm.copy(content = content)
        }

        /** A chat derived from the real DM `m.bridge`; requests and groups are hand-made (the dummies have neither). */
        fun portal(id: String, receiver: String, request: Boolean = false, group: Boolean = false, ts: Long = 1000, unread: Int = 0,
                   tags: Map<String, JsonElement> = emptyMap(), inboxFolder: String? = null): Room {
            var content = bridge(receiver, inboxFolder).content
            if (request) content = content.setting(listOf("channel", "com.beeper.message_request"), true)
            if (group) content = content.setting(listOf("com.beeper.room_type"), null).setting(listOf("com.beeper.room_type.v2"), null)
            return Room(id).apply {
                apply(JoinedRoom(
                    EventList(listOf(Fixture.event("state_bridge_dm").copy(content = content))),
                    JoinedRoom.Timeline(listOf(message("\$m$id", ts)), false, "p"),
                    unreadNotifications = JoinedRoom.UnreadCounts(unread, 0),
                ))
                this.tags = tags
            }
        }

        fun named(id: String, name: String, receiver: String, request: Boolean = false, ts: Long = 1000,
                  tags: Map<String, JsonElement> = emptyMap(), inboxFolder: String? = null): Room =
            portal(id, receiver, request, ts = ts, tags = tags, inboxFolder = inboxFolder).apply {
                apply(stateUpdate(state("m.room.name", mapOf("name" to name))))
            }

        /**
         * Three requests over the dummies at `NOW` (the iOS app's DEBUG request rooms): Kestrel Bikes (tamoveska, 2 h
         * ago, two messages), Lena Ortiz (kivostudio, 5 h ago), crypto.wins.daily (tamoveska, a day ago).
         */
        fun requests(): List<Room> = listOf(
            Triple("Kestrel Bikes", 120L, 2), Triple("Lena Ortiz", 300L, 1), Triple("crypto.wins.daily", 1440L, 1),
        ).mapIndexed { i, (name, minutesAgo, count) ->
            val receiver = listOf(Fixture.tamoLogin, Fixture.kivoLogin)[i % 2]
            val start = NOW - minutesAgo * 60_000
            val sender = IGAdapter.user("1784140000000000${i + 1}")
            portal("!request-$i", receiver, request = true, ts = start).apply {
                apply(stateUpdate(state("m.room.name", mapOf("name" to name))))
                apply(timeline(*(1 until count).map { message("\$request-$i-$it", start + it * 40_000, sender) }.toTypedArray()))
            }
        }
    }
}
