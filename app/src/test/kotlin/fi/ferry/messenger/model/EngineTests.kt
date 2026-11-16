package fi.ferry.messenger.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the engine makes of Instagram's captures (`engine_*.json`, written by core/chat's `go test ./chat -update`),
 * applied as the app applies it, then read by `Timeline`, `Inbox` and the cards: the iOS app's EngineTests, so both
 * apps check the same contract. The engine's own rules (unread, folders, live changes, typing, IDs) are tested in Go.
 */
class EngineTests {
    private val tamo = Fixture.tamoLogin
    private val kivo = Fixture.kivoLogin
    private val me = IGAdapter.ME
    private val now = 1_791_900_000_000L

    private fun room(updates: List<IG.Update>): Room = Room(updates.first().room!!).apply {
        ownUserID = me
        updates.forEach { IGAdapter.apply(it, this) }
    }

    private fun room(vararg events: Event): Room = Room("!ig-test").apply { apply(timeline(*events)) }

    @kotlinx.serialization.Serializable
    private data class LoginFixture(val login: IG.Login, val rooms: List<IG.Update>)

    /** `IGLogin`'s answer and the chats it queued. */
    private fun login(name: String): LoginFixture = Fixture.engine("login_$name")

    /** The DM after P0's script as `IGReadChat` makes it, as tamoveska's chat or (the same capture) kivostudio's. */
    private fun thread(account: String): Room =
        room(listOf(Fixture.engine<Map<String, IG.Update>>("thread_tamo").getValue(if (account == tamo) "tamo" else "kivo")))

    private fun events(name: String, key: String): List<Event> = Fixture.engine<Map<String, List<Event>>>(name).getValue(key)

    @Test fun loginAndAccount() {
        val tamoLogin = login("tamo").login
        val kivoLogin = login("kivo").login
        assertEquals(tamo, tamoLogin.account)
        assertEquals("tamoveska", tamoLogin.viewer.username)
        assertEquals(false, tamoLogin.viewer.isProfessionalAccount)
        val account = IGAdapter.login(kivo, kivoLogin.viewer, "CONNECTED", since = now)
        assertTrue(account.isProfessional)
        assertEquals("kivostudio", account.profile?.username)
        assertEquals("Kivo Studio", account.displayName)
        assertEquals(BridgeAPI.Login.Health.Connected, account.health)
        val reconnecting = IGAdapter.login(kivo, kivoLogin.viewer, "TRANSIENT_DISCONNECT", since = now, transientSince = now - 300_000)
        assertEquals(BridgeAPI.Login.Health.Reconnecting, reconnecting.health(now))
    }

    /** One Instagram thread seen by both dummies is two chats, each in its own account's scope. */
    @Test fun theDummiesDMIsOneChatPerAccount() {
        val tamoLogin = login("tamo")
        val kivoLogin = login("kivo")
        val a = room(tamoLogin.rooms)
        val b = room(kivoLogin.rooms)
        assertNotEquals(a.id, b.id)
        assertEquals(tamo, a.bridge?.receiver)
        assertEquals(kivo, b.bridge?.receiver)
        assertEquals(true, a.bridge?.isDM)
        assertEquals(kivo, a.bridge?.channelID) // the other person, here our other account
        assertEquals("Kivo Studio", a.name)
        assertEquals("Tamo Veska", b.name)
        assertEquals(Inbox.Folder.CHATS, Inbox.folder(a))
        assertEquals(listOf(a.id), Inbox.rooms(listOf(a, b), Inbox.Scope.Account(tamo), Inbox.Folder.CHATS, setOf(tamo, kivo)).map { it.id })
        // A removed account's chats hide.
        assertEquals(listOf(a.id), Inbox.rooms(listOf(a, b), Inbox.Scope.All, Inbox.Folder.CHATS, setOf(tamo)).map { it.id })
        val accounts = listOf(tamoLogin.login, kivoLogin.login).map { IGAdapter.login(it.account, it.viewer, "CONNECTED", since = now) }
        assertEquals("kivostudio", Inbox.contactHandle(a, accounts))
    }

    /** Names and @handles come with the thread: no profile lookups, no localparts. */
    @Test fun peopleCarryNamesAndHandles() {
        val update = login("tamo").rooms.first()
        val contact = IGAdapter.user(kivo)
        assertEquals("kivostudio", update.profiles?.get(contact)?.handle)
        assertEquals("Kivo Studio", update.profiles?.get(contact)?.name)
        val room = room(listOf(update))
        assertEquals(contact, Inbox.contactUserID(room, me))
        assertEquals("Kivo Studio", room.displayName(contact))
        assertNotNull(room.avatarURL) // the contact's picture, a CDN link
        assertEquals(2, Inbox.memberCount(room))
        // Chat Info's members: the contact, then us; their picture and handle from what came with the chat.
        val members = Inbox.members(room, me, update.profiles.orEmpty())
        assertEquals(listOf(contact, me), members.map { it.id })
        assertEquals(listOf("Kivo Studio", "You"), members.map { it.name })
        assertEquals("kivostudio", members.first().handle)
        assertNotNull(members.first().avatarURL)
    }

    /** Instagram's folders: `system_folder` PENDING/SPAM is a request, `folder` GENERAL is General. */
    @Test fun requestsAndGeneralComeFromTheFolders() {
        assertEquals(BridgeInfo.InboxFolder.PRIMARY, room(login("kivo").rooms).bridge?.folder)
        val folders = Fixture.engine<Map<String, IG.Update>>("folders_kivo")
        assertEquals(Inbox.Folder.GENERAL, Inbox.folder(room(listOf(folders.getValue("general")))))
        val request = room(listOf(folders.getValue("pending")))
        assertEquals(Inbox.Folder.REQUESTS, Inbox.folder(request))
        assertFalse(Inbox.allowsReceipts(request)) // opening it must send nothing
        assertTrue(Inbox.isRequest(room(listOf(folders.getValue("spam")))))
    }

    @Test fun theThreadReducesToItsMessages() {
        val room = thread(tamo)
        val messages = Timeline.reduce(room, me)
        assertEquals(17, messages.size)
        val back = messages.last()
        assertEquals("P0 capture: back", back.body)
        assertTrue(back.isOwn)
        assertNull(back.reply) // Instagram sends `replied_to_message_id: ""` on every message that isn't a reply
        assertFalse(back.isSeen) // kivostudio hadn't read it when this was captured
        assertNotNull(back.txnID) // our offline threading ID, which clears the local echo
        val edited = messages.first { it.body == "P0 capture: a reply, edited" }
        assertTrue(edited.isEdited)
        assertFalse(edited.isOwn)
        assertEquals(listOf(Message.Reaction("😂", listOf(me), edited.reactions.firstOrNull()?.myEventID)), edited.reactions)
        assertEquals(MessageKind.Unsent, edited.reply?.kind) // its target was unsent: the quote says so
        assertTrue(messages.any { it.kind == MessageKind.Voice })
        assertTrue(messages.any { it.kind == MessageKind.Video })
        assertTrue(messages.any { it.kind == MessageKind.ViewOnce })
        assertEquals("You: P0 capture: back", Inbox.preview(room, me)?.text)
        assertFalse(room.isUnread) // the newest is our own
        assertTrue(thread(kivo).isUnread) // from the other side: kivostudio's watermark is older
    }

    @Test fun olderPagesPrependUntilTheStart() {
        val room = thread(tamo)
        val token = room.prevBatch!!
        assertNotEquals(IGAdapter.START_TOKEN, token)
        val oldest = room.events.first { it.isBaseMessage }.originServerTS!!
        room.prepend(Fixture.engine<Map<String, MessagesPage>>("older_tamo").getValue("page"), token)
        assertTrue(Timeline.reduce(room, me).size > 17)
        assertTrue(room.events.first { it.isBaseMessage }.originServerTS!! < oldest)
        assertTrue(room.reachedStart) // that page is the rest of the dummies' DM: Instagram reports nothing older
    }

    /** A GIF from the other dummy: its WebP, marked so the card plays it; this one came with no still preview. */
    @Test fun aGIFIsAnAnimatedImage() {
        val event = events("event_gif_kivo", "live").first()
        assertEquals(MessageKind.Gif, MessageKind.of(event.type, event.content))
        val info = MediaInfo(event.content)
        assertEquals("https://scontent.example.invalid/v/g001.webp", info.url)
        assertEquals("image/webp", info.mimetype)
        assertEquals(listOf(240.0, 240.0), listOf(info.width, info.height))
        assertNull(info.thumbnailURL)
        assertEquals("GIF", MessageKind.Gif.media?.label)

        // The mailbox's copy: no media, still a GIF, so the inbox says so; opening the chat reads the rest.
        val bare = events("event_gif_kivo", "mailbox").first()
        assertEquals(MessageKind.Gif, MessageKind.of(bare.type, bare.content))
        assertNull(MediaInfo(bare.content).url)
    }

    /**
     * Photos and videos from Instagram's in-chat camera (live 2026-10-24, `ig_camera_media_tamo`): kept in chat, the
     * same cards as camera-roll media; view once and allow replay stay a view-once line. Ferry showed the kept photo as
     * view-once and the video as a message it couldn't show.
     */
    @Test fun cameraPhotosAndVideos() {
        val thread = Timeline.reduce(events("camera_media_tamo", "thread"), me)
        assertEquals(listOf(MessageKind.ViewOnce, MessageKind.ViewOnce, MessageKind.Image, MessageKind.Video), thread.map { it.kind })
        assertEquals("https://scontent.example.invalid/v/c004.jpg", MediaInfo(thread[2].content).url)
        assertEquals("https://scontent.example.invalid/v/c010.mp4", MediaInfo(thread[3].content).url)
    }

    /** Three photos sent at once from the Instagram app: one message with every photo, in order. */
    @Test fun photosSentTogetherAreOneMessageWithAll() {
        val messages = Timeline.reduce(events("message_photos_tamo", "thread"), me)
        assertEquals(1, messages.size)
        val info = MediaInfo(messages.first().content)
        assertEquals(MessageKind.Image, messages.first().kind)
        assertEquals("https://scontent.example.invalid/v/f040.jpg", info.url) // the first: inbox, quotes
        assertEquals((0 until 3).map { "https://scontent.example.invalid/v/f0${40 + 2 * it}.jpg" }, info.album.map { MediaInfo(it).url })
        assertEquals(List(3) { MessageKind.Image }, info.album.map { MessageKind.of("m.room.message", it) })
        assertEquals(468.0 / 750, MediaInfo(info.album[2]).aspect!!, 1e-9)
        assertEquals("3 photos", info.albumLabel)

        // The mailbox's copy: no attachments, still a photo, and no album to stack.
        val bare = events("message_photos_tamo", "mailbox").first().content
        assertEquals(MessageKind.Image, MessageKind.of("m.room.message", bare))
        assertTrue(MediaInfo(bare).album.isEmpty())

        // Photos and videos together: each item says which.
        val mixed = MediaInfo(events("message_photos_tamo", "mixed").first().content)
        assertEquals(listOf(MessageKind.Image, MessageKind.Video), mixed.album.map { MessageKind.of("m.room.message", it) })
        assertEquals("https://scontent.example.invalid/v/f047.jpg", MediaInfo(mixed.album[1]).thumbnailURL)
        assertEquals("2 photos and videos", mixed.albumLabel)
    }

    /** Instagram's own lines and content the app doesn't know are lines across the thread, never a bubble. */
    @Test fun chatEventsAndUnknownContentAreLines() {
        val events = events("chat_events", "events")
        val messages = Timeline.reduce(events, me, receipts = mapOf(IGAdapter.user(kivo) to Room.Receipt("mid.7", 7_000)))
        val cant = MessageKind.Unsupported("a message Ferry can't show")
        assertEquals(
            listOf(MessageKind.Text, MessageKind.Text, MessageKind.ChatEvent, cant, MessageKind.Unsupported("a poll"), MessageKind.Text,
                MessageKind.ChatEvent, cant),
            messages.map { it.kind },
        )
        assertEquals("Kivo named the group Weekend", messages[2].body)
        assertEquals(listOf("mid.5"), messages.filter { it.isSeen }.map { it.id }) // "Seen" stays on our last real message

        // In a group: a line takes no sender name or avatar, and splits the run it lands in.
        val rows = Timeline.layout(messages, isGroup = true)
        assertEquals(listOf(true, true, false, false, false, false, false, false), rows.map { it.groupSender != null })
        assertEquals(Timeline.GroupPosition.LAST, rows[1].position)

        // The inbox: an event names who did it; what the app can't show says who sent what.
        fun preview(count: Int) = Inbox.preview(room(*events.take(count).toTypedArray()), me)?.text
        assertEquals("Kivo named the group Weekend", preview(3))
        assertEquals("Sent a poll", preview(5))
        assertEquals("You sent a message Ferry can't show", preview(8))
    }

    /** Their message, our "Seen" on it, our reply, and their "Seen" on ours, live on tamoveska's chat. */
    @Test fun liveMessagesReadsAndReceipts() {
        val room = room(Fixture.engine<Map<String, List<IG.Update>>>("live_tamo").getValue("live"))
        assertFalse(room.isUnread)
        val own = room.lastMessage!!
        assertEquals(me, own.sender)
        assertNotNull(own.unsigned?.transactionID) // clears our local echo
        // kivostudio read it on another device three minutes later: "Seen" on our message.
        assertEquals(own.eventID, room.receipts[IGAdapter.user(kivo)]?.eventID)
        assertTrue(Timeline.reduce(room, me).last().isSeen)
    }

    /** P0's run replayed live on tamoveska's chat: text, reply, reactions, edit, unsend, "Seen" both ways. */
    @Test fun p0RunReplaysLive() {
        val room = room(Fixture.engine<Map<String, List<IG.Update>>>("events_tamo").getValue("replay"))
        val messages = Timeline.reduce(room, me)
        val unsent = messages.first { it.kind == MessageKind.Unsent }
        val reply = messages.first { it.body == "P0 capture: a reply, edited" }
        assertTrue(reply.isEdited)
        assertEquals(listOf("😂"), reply.reactions.map { it.key })
        assertEquals(listOf(me), reply.reactions.first().senders)
        assertEquals(unsent.eventID, reply.reply?.eventID)
        assertNotNull(room.related[unsent.eventID!!]) // the quoted message came with the reply
        val back = messages.last()
        assertEquals("P0 capture: back", back.body)
        assertTrue(back.isSeen)
        assertFalse(room.isUnread)
        // Our "Seen" moved our own m.read to their reply: where a "New messages" line would start.
        assertEquals(Room.Receipt(reply.eventID!!, 1_792_172_798_127), room.receipts[me])
    }

    /** Our watermark is our m.read and never makes anything "Seen"; the "New messages" line starts after it. */
    @Test fun ourWatermarkStartsTheNewMessagesLine() {
        val room = thread(kivo)
        assertEquals(1_792_115_019_051, room.receipts[me]?.ts)
        assertEquals(room.events.lastOrNull { it.isBaseMessage && (it.originServerTS ?: 0) <= 1_792_115_019_051 }?.eventID, room.receipts[me]?.eventID)
        val messages = Timeline.reduce(room, me)
        assertNull(messages.firstOrNull { it.isSeen && !it.isOwn })
        // After our watermark and after our own last message (kivostudio's edited reply), so on "back".
        val rows = Timeline.layout(messages, unreadAfter = room.receipts[me]?.ts)
        assertEquals(listOf(messages.last().id), rows.filter { it.startsUnread }.map { it.id })
    }

    /** A reply and an emoji reaction to tamoveska's story, from the Instagram app: the story, what was sent, and the line. */
    @Test fun storyRepliesAndReactionsKeepWhatWasSent() {
        val threadEvents = events("story_replies_kivo", "thread")
        val story = Timeline.reduce(threadEvents, me)
        val unresolved = MessageKind.Share(MessageKind.ShareType.STORY, MessageKind.ShareState.UNRESOLVED)
        assertEquals(listOf(unresolved, unresolved), story.map { it.kind }) // "Load story"
        assertEquals(listOf("😢", "Droopy"), story.map { it.shareText })
        assertEquals(listOf("Reacted to @Tamo Veska's story", "Replied to @Tamo Veska's story"), story.map { ShareCaption.of(it.content).eyebrow })
        assertEquals("tamoveska", ShareCaption.of(story[1].content).author) // "Story · @tamoveska"
        assertNull(Timeline.reduce(thread(tamo), me).last().shareText) // "P0 capture: back"

        // The inbox, from the mailbox's copies (one event each): what was sent, "You: " once on our side.
        fun preview(event: Event) = Inbox.preview(room(event), me)?.text
        assertEquals(listOf("You: 😢", "You: Droopy"), events("story_replies_kivo", "mailbox_sender").map(::preview))
        assertEquals(listOf("😢", "Droopy"), events("story_replies_kivo", "mailbox_receiver").map(::preview))
        assertEquals("You: Droopy", preview(threadEvents[1])) // the same once the thread is read

        // Any other share's mailbox copy (no `xma`, no text) is Instagram's snippet, which names the sender itself.
        assertEquals("You sent an attachment.", preview(events("story_replies_kivo", "bare").first()))

        // An expired story: its notice, then what was sent.
        val expired = events("story_replies_kivo", "expired").first().content
        assertEquals(MessageKind.Notice, MessageKind.of("m.room.message", expired))
        assertEquals(listOf("Story unavailable", "Droopy"), listOf(expired["body"].string, expired[MessageKind.SHARE_TEXT_KEY].string))
    }

    /** New Message: a draft chat until the first message makes the thread (`RoomMoved`); our DM found by FBID. */
    @Test fun draftsAndExistingDMs() {
        val drafts = Fixture.engine<Map<String, List<IG.Update>>>("drafts")
        val draft = drafts.getValue("draft").first()
        assertEquals("Draft", draft.kind)
        val room = room(listOf(draft))
        assertEquals(tamo, room.bridge?.receiver)
        assertEquals("17841400000000999", room.bridge?.channelID) // whom the first message goes to
        assertEquals(true, room.bridge?.isDM)
        assertEquals("Sample Person", room.name)
        assertEquals(IGAdapter.START_TOKEN, room.prevBatch)
        assertEquals("Tamo Veska", room.memberName(me))
        assertEquals("sample.person", draft.profiles?.get(IGAdapter.user("17841400000000999"))?.handle)
        assertEquals(0, Timeline.reduce(room, me).size)

        val moved = drafts.getValue("moved").last()
        assertEquals("RoomMoved", moved.kind)
        assertEquals(drafts.getValue("kivo_draft").first().room, moved.room)
        val dm = room(login("tamo").rooms)
        assertEquals(dm.id, moved.to)
        assertEquals(dm.id, Inbox.existingDM(kivo, tamo, listOf(dm))?.id)
    }

    /** Shares (Instagram's XMA, with made-up authors): the bridge's content shapes, so the cards read them. */
    @Test fun sharesBecomeTheBridgesShapes() {
        val reel = events("shares", "reel").first().content
        assertEquals(MessageKind.Share(MessageKind.ShareType.REEL, MessageKind.ShareState.UNRESOLVED), MessageKind.of("m.room.message", reel))
        assertEquals("sample.travels", ShareCaption.of(reel).author)
        assertEquals("https://scontent.example.invalid/v/f101.jpg", MediaInfo(reel).previewURL)

        val post = events("shares", "post").first().content
        assertEquals(MessageKind.Share(MessageKind.ShareType.POST, MessageKind.ShareState.READY), MessageKind.of("m.room.message", post))
        assertEquals("Golden hour", ShareCaption.of(post).caption)

        val link = events("shares", "link").first().content
        assertEquals(MessageKind.Link, MessageKind.of("m.room.message", link))
        assertEquals("An article", LinkPreview.of(link)?.title)

        assertEquals("Story unavailable", events("shares", "expired").first().content["body"].string)

        // "Load video": the hidden edit swaps the card to the reel itself.
        val room = thread(tamo)
        room.apply(JoinedRoom(timeline = JoinedRoom.Timeline(events("shares", "reel"), limited = false)))
        assertEquals(MessageKind.Share(MessageKind.ShareType.REEL, MessageKind.ShareState.UNRESOLVED), Timeline.reduce(room, me).last().kind)
        room.apply(JoinedRoom(timeline = JoinedRoom.Timeline(events("shares", "resolved"), limited = false)))
        val after = Timeline.reduce(room, me).last()
        assertEquals(MessageKind.Share(MessageKind.ShareType.REEL, MessageKind.ShareState.READY), after.kind)
        assertFalse(after.isEdited)
        assertEquals("https://scontent.example.invalid/v/f102.mp4", MediaInfo(after.content).url)
        assertEquals(12_500L, MediaInfo(after.content).duration)
        assertEquals("sample.travels", ShareCaption.of(after.content).author)
    }

    /**
     * A sticker, then a GIF, from Instagram's GIPHY picker (live 2026-10-20, `ig_animated_images_tamo`): both play
     * like a GIF; the sticker is named so and drawn as one. Ferry said it couldn't show either.
     */
    @Test fun giphyStickersAndGIFsAreAnimatedImages() {
        val thread = Timeline.reduce(events("animated_images_tamo", "thread"), me)
        assertEquals(listOf(MessageKind.Sticker, MessageKind.Gif), thread.map { it.kind })
        assertEquals(listOf(true, true), thread.map { MediaInfo(it.content).isAnimated })
        assertEquals("https://scontent.example.invalid/v/a001.webp", MediaInfo(thread[0].content).url)
        assertEquals("Sticker", MessageKind.Sticker.media?.label)

        // The mailbox's copies: no media, named from Instagram's snippet until the thread is read.
        fun preview(event: Event) = Inbox.preview(room(event), me)?.text
        val receiver = events("animated_images_tamo", "mailbox_receiver")
        assertEquals(listOf(MessageKind.Sticker, MessageKind.Gif), receiver.map { MessageKind.of(it.type, it.content) })
        assertEquals(listOf(null, null), receiver.map { MediaInfo(it.content).url })
        assertEquals(listOf("Sticker", "GIF"), receiver.map(::preview))
        assertEquals(listOf("You: Sticker", "You: GIF"), events("animated_images_tamo", "mailbox_sender").map(::preview))
    }

    /**
     * The same chat as its mailbox copy has it, only the newest message, which is past our watermark: our m.read still
     * comes (on the only message, with our watermark's time), so the line has its place. Without it an unread live
     * chat opened with no "New messages" line (QA 2026-10-20).
     */
    @Test fun ourWatermarkComesWithAnUnreadMailboxCopy() {
        val room = room(listOf(Fixture.engine<IG.Update>("unread_window_kivo")))
        assertTrue(room.isUnread)
        assertEquals(1_792_115_019_051L, room.receipts[me]?.ts)
        val messages = Timeline.reduce(room, me)
        assertEquals(1, messages.size)
        val rows = Timeline.layout(messages, unreadAfter = room.receipts[me]?.ts)
        assertEquals(listOf(messages.first().id), rows.filter { it.startsUnread }.map { it.id })
    }
}
