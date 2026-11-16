package fi.ferry.messenger.ui

import android.icu.lang.UCharacter
import android.net.Uri
import android.icu.lang.UProperty
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.MediaFiles
import fi.ferry.messenger.Outgoing
import fi.ferry.messenger.VoicePlayer
import fi.ferry.messenger.VoiceRecorder
import fi.ferry.messenger.model.AccountIdentity
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.LinkPreview
import fi.ferry.messenger.model.Links
import fi.ferry.messenger.model.MediaInfo
import fi.ferry.messenger.model.Message
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.Room
import fi.ferry.messenger.model.ShareCaption
import fi.ferry.messenger.model.Timeline
import fi.ferry.messenger.model.Timeline.GroupPosition
import fi.ferry.messenger.model.UserProfile
import fi.ferry.messenger.model.parseURL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The composer's items' IDs, this process's (main thread). */
private var nextItemID = 0L

/** DESIGN §6.1 groups: the sender's 28 dp avatar beside the last bubble of a run, and the indent it leaves. */
private val GROUP_AVATAR = 28.dp
private val GROUP_INDENT = GROUP_AVATAR + 6.dp

/**
 * T1, one chat (DESIGN §5 T1, §6), locked to the account it belongs to: `Timeline.reduce(room)`, laid out from the
 * bottom so it opens on the newest message and older pages land above without moving it. Read-only until A4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(roomID: String, back: () -> Unit, relogin: (String) -> Unit) {
    BackHandler(onBack = back)
    val room = AppModel.rooms[roomID]
    if (room == null) {
        Empty(Icons.chats, "Chat not found", "It's no longer in this account's chats.")
        return
    }
    val me = AppModel.ME
    val identity = identityOf(room)
    val profiles = AppModel.profiles
    val messages = Timeline.reduce(room, me)
    val isGroup = room.bridge?.isDM == false
    // How far we'd read when the chat opened unread, taken once, before our "Seen" moves it: the "New messages" line.
    val unreadAfter = remember(roomID) { if (room.isUnread) room.receipts[me]?.ts else null }
    val rows = Timeline.layout(messages, isGroup, unreadAfter)
    val lastOwn = messages.lastOrNull { it.isOwn && !it.kind.isChatEvent }?.id
    val typing = room.typing.any { it != me }
    val people = People(room, profiles)
    val seen = if (isGroup) messages.firstOrNull { it.seenBy.isNotEmpty() } else null
    // Everyone but us and its sender: "Seen by everyone".
    val seenLine = seen?.let { Timeline.seenByLine(it.seenBy.map(people::firstName), it.seenBy.size >= Inbox.memberCount(room) - if (it.isOwn) 1 else 2) }
    val times = rememberThreadTimes()
    val list = rememberLazyListState()
    var reactionsOf by remember { mutableStateOf<Message?>(null) }
    var info by remember(roomID) { mutableStateOf(false) }
    var person by remember { mutableStateOf<String?>(null) }
    var draft by rememberSaveable(roomID, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var replyingTo by remember(roomID) { mutableStateOf<Message?>(null) }
    var editing by remember(roomID) { mutableStateOf<Message?>(null) }
    var replyingInstead by remember(roomID) { mutableStateOf(false) }
    // Photos and videos waiting above the field, in the order they go (iOS 1.7: up to 10). Not kept past the thread.
    val items = remember(roomID) { mutableStateListOf<ComposerItem>() }
    val context = LocalContext.current
    /** Each waits as "Preparing…" until `Outgoing` has it ready; one that can't be sent goes, with why. */
    fun add(uris: List<Uri>) {
        val left = MAX_ATTACHMENTS - items.size
        if (uris.size > left) AppModel.actionError = "You can send up to $MAX_ATTACHMENTS photos and videos at a time."
        for (uri in uris.take(max(left, 0))) {
            val item = ComposerItem(nextItemID++, context.contentResolver.getType(uri)?.startsWith("video/") == true)
            items += item
            Outgoing.prepare(context, uri, wanted = { items.any { it.id == item.id } }) { result ->
                val i = items.indexOfFirst { it.id == item.id }
                if (i < 0) { result.getOrNull()?.file?.delete(); return@prepare } // removed meanwhile
                result.onSuccess { items[i] = item.copy(ready = it) }
                    .onFailure { items.removeAt(i); AppModel.actionError = it.message ?: "This can't be sent." }
            }
        }
    }
    fun removeItem(id: Long) {
        items.firstOrNull { it.id == id }?.let { items.remove(it); it.ready?.file?.delete() }
    }
    // Android's photo picker: no permission, only what's picked, numbered in the order picked (D7). The several-at-once
    // picker refuses a limit under two, so the last slot gets the single one (A22).
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { add(listOf(it)) } }
    val pickSome = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { add(it) }
    fun library() {
        val left = MAX_ATTACHMENTS - items.size
        val media = ActivityResultContracts.PickVisualMedia.ImageAndVideo
        if (left >= 2) pickSome.launch(PickVisualMediaRequest.Builder().setMediaType(media).setMaxItems(left).setOrderedSelection(true).build())
        else if (left == 1) pickOne.launch(PickVisualMediaRequest(media))
    }
    var confirmingDelete by remember(roomID) { mutableStateOf(false) }
    val login = AppModel.accounts.firstOrNull { it.id == room.bridge?.receiver }
    val bar = Inbox.threadBar(room, login?.health, replyingInstead)
    val scope = rememberCoroutineScope()
    val actions = MessageActions(
        react = { key, message -> AppModel.react(key, message, room) },
        reply = { editing = null; replyingTo = it },
        edit = { replyingTo = null; editing = it; draft = TextFieldValue(it.body, TextRange(it.body.length)) },
        unsend = { AppModel.unsend(it, room) },
        retry = { AppModel.retry(it, room) },
        discard = { AppModel.discard(it, room) },
        readOnly = !Inbox.allowsReceipts(room),
        firstName = { people.firstName(it) },
        // iOS 1.1: every photo and video of the set at once (the pile shows the downloads), then the share sheet.
        shareAll = { message ->
            val items = MediaInfo(message.content).album.map(::MediaInfo)
            val local = items.map { item -> item.url?.let(AppModel::localFile) }
            scope.launch {
                val files = withContext(Dispatchers.IO) {
                    coroutineScope { items.mapIndexed { i, item -> async { local[i] ?: item.url?.let { MediaFiles.download(it, item.mimetype) } } }.awaitAll() }
                }
                if (files.all { it != null }) share(context, files.filterNotNull()) else AppModel.refreshMedia(room) // the links may have expired
            }
        },
    )
    fun send() {
        val text = draft.text.trim()
        val media = items.mapNotNull { it.ready }
        if (media.size < items.size) return // one is still being prepared
        if (text.isEmpty() && media.isEmpty() || text.length > Timeline.MAX_TEXT_LENGTH) return
        val edited = editing
        if (edited != null) {
            if (text.isNotEmpty() && text != edited.body) AppModel.edit(edited, text, room)
        } else {
            // The photos and videos first, one message each in their order (Instagram's web send takes one attachment),
            // then the text as its own message (Instagram drops captions); the chat's send line keeps that order (A12).
            // A reply goes with the text when there is one, else with the first.
            media.forEachIndexed { i, attachment -> AppModel.sendMedia(attachment, replyingTo?.eventID?.takeIf { i == 0 && text.isEmpty() }, room) }
            items.clear()
            if (text.isNotEmpty()) AppModel.send(text, replyingTo?.eventID, room)
        }
        draft = TextFieldValue()
        replyingTo = null
        editing = null
    }

    LaunchedEffect(roomID) { AppModel.openThread(room) } // the whole thread: reactions, edits, reply targets
    // On open and on each new message while it shows; the engine decides whether a "Seen" goes (unread, not a request).
    LaunchedEffect(room.events.lastOrNull()?.eventID) { AppModel.markRead(room) }
    // iOS 1.5: a chat that opens unread holds its "New messages" line near the top, the last message read above it,
    // unless what's new reaches less than a quarter screen past it: then it opens at the end. Hidden until placed (1 s
    // at most). A touch, the jump button or a send lets go. iOS 1.6.5: an older page that brings the last message read,
    // or moves the line further back (a mailbox copy that was all unread), places it again while it's held.
    val lineAnchor = rows.indexOfFirst { it.startsUnread }.let { i -> rows.getOrNull(i)?.id to (i > 0) }
    var holdsLine by remember(roomID) { mutableStateOf(unreadAfter != null && lineAnchor.first != null) }
    var revealed by remember(roomID) { mutableStateOf(!holdsLine) }
    LaunchedEffect(roomID) { if (!revealed) { delay(1_000); revealed = true } }
    LaunchedEffect(roomID, lineAnchor) {
        if (!holdsLine) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.viewportSize.height }.first { it > 0 }
        val lineRow = rows.indexOfFirst { it.startsUnread }
        val line = rows.size - 1 - lineRow + if (typing) 1 else 0 // reversed: index 0 is the newest, at the bottom
        list.scrollToItem(line) // the line's row near the bottom (higher up when the chat's start is close)
        val info = list.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val lineItem = info.visibleItemsInfo.firstOrNull { it.index == line }
        val context = if (lineRow > 0) info.visibleItemsInfo.firstOrNull { it.index == line + 1 }?.size ?: 0 else 0
        // Reversed, offsets count up from the bottom: the line's top edge goes to the top, less the last message read.
        if (lineItem != null) list.scrollBy(-(info.viewportEndOffset - context - (lineItem.offset + lineItem.size)).toFloat())
        val below = -list.scrollBy(-1e7f) // what's under the screen there (it ends at the end)
        // The first row loaded is already unread: the last message read is still to come with a page, so hold on, and
        // stay hidden (up to the second) rather than show the line moving as the pages land.
        val waiting = lineRow == 0 && !room.reachedStart
        if (below - context > viewport / 4 || waiting) list.scrollBy(below) else holdsLine = false
        if (!waiting) revealed = true
    }
    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) holdsLine = false } }
    // A new message while at the end, or our own sent from here: follow it. Ours from Instagram's app arrives as an
    // event like theirs and leaves the scroll alone (iOS 1.5).
    LaunchedEffect(messages.lastOrNull()?.id, typing) {
        val sentHere = messages.lastOrNull()?.isLocalEcho == true
        if (sentHere) holdsLine = false
        if (!holdsLine && (list.firstVisibleItemIndex <= 1 || sentHere)) list.animateScrollToItem(0)
    }
    // The jump-to-newest button (iOS 1.5): away from the end, once scrolling rests (at once while the line's held), with
    // what others sent since it appeared.
    val density = LocalDensity.current
    val far by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > with(density) { 60.dp.toPx() } } }
    var jumpSince by remember(roomID) { mutableStateOf<Long?>(null) }
    val showsJump = revealed && far && (jumpSince != null || holdsLine || !list.isScrollInProgress)
    LaunchedEffect(showsJump) { jumpSince = if (showsJump) jumpSince ?: room.lastActivity else null }
    // The composer's typing state as it changes (the engine throttles it, never in a request); off when leaving.
    // Derived, so a keystroke recomposes only the composer, not the whole thread.
    val composing by remember(roomID) { derivedStateOf { draft.text.isNotEmpty() && editing == null } }
    LaunchedEffect(composing) { AppModel.setTyping(composing, room) }
    // Reply, Edit, "Reply instead" and a chat New Message opened bring up the keyboard, as on iOS.
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(replyingTo, editing, replyingInstead) {
        val opened = AppModel.focusComposer == roomID
        if (opened) AppModel.focusComposer = null
        if (bar != Inbox.ThreadBar.REQUEST && (opened || replyingTo != null || editing != null || replyingInstead)) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    DisposableEffect(roomID) {
        // On screen: no notification for it, and any there was goes.
        AppModel.visibleRoom = roomID
        fi.ferry.messenger.Notifier.clear(context, roomID)
        onDispose {
            if (AppModel.visibleRoom == roomID) AppModel.visibleRoom = null
            AppModel.setTyping(false, room); VoicePlayer.stop(); VoiceRecorder.cancel()
            // What waited above the field goes with the thread (one still being prepared is dropped when it's done).
            items.forEach { it.ready?.file?.delete() }
            items.clear()
        }
    }
    var viewer by remember { mutableStateOf<Viewer?>(null) }
    // GIFs play in a chat, not in a request (nothing there should move before it's accepted).
    val media = MediaContext(room, autoplay = !Inbox.isRequest(room)) { viewer = it }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (bar == Inbox.ThreadBar.SIGNED_OUT && login != null) {
                RecoveryBar(identity?.name ?: login.displayName) { relogin(login.id) }
            } else if (bar == Inbox.ThreadBar.REQUEST) {
                RequestBar(AppModel.contactHandle(room)?.let { "@$it" } ?: room.name, identity,
                    accept = { AppModel.accept(room) }, delete = { confirmingDelete = true }, replyInstead = { replyingInstead = true })
            } else {
                Composer(
                    draft, { draft = it }, identity, login,
                    mode = editing?.let { ComposerMode.Editing }
                        ?: replyingTo?.let { ComposerMode.Replying(people.firstName(it.sender), it.kind.media?.label ?: it.body) }
                        ?: ComposerMode.AcceptsRequest.takeIf { bar == Inbox.ThreadBar.REPLY_ACCEPTS },
                    cancel = {
                        if (editing != null) draft = TextFieldValue()
                        if (replyingTo == null && editing == null) { replyingInstead = false; draft = TextFieldValue() } // back to the request bar
                        replyingTo = null
                        editing = null
                    },
                    send = ::send,
                    focus = focus,
                    items = items,
                    attach = if (bar == Inbox.ThreadBar.COMPOSER && editing == null) AttachActions(library = ::library) else null,
                    removeAttachment = ::removeItem,
                    sendVoice = if (bar == Inbox.ThreadBar.COMPOSER && editing == null) {
                        { note -> AppModel.sendMedia(note, replyingTo?.eventID, room); replyingTo = null }
                    } else null,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (identity != null) {
                Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(identity.color.wash, Color.Transparent))))
            }
            Column(Modifier.padding(padding)) {
                ThreadHeader(room, identity, back, openInfo = { info = true }) { MoreMenu(room) { info = true } }
                AppModel.toast?.let { Notice(it, error = false) { AppModel.toast = null } }
                AppModel.actionError?.let { Notice(it, error = true) { AppModel.actionError = null } }
                CompositionLocalProvider(LocalMedia provides media, LocalViewConfiguration provides quickHold(LocalViewConfiguration.current)) { Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().alpha(if (revealed) 1f else 0f), state = list, reverseLayout = true,
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                        if (typing) item("typing") { TypingBubble(Modifier.padding(top = 10.dp, start = if (isGroup) GROUP_INDENT else 0.dp)) }
                        items(rows.asReversed(), key = { it.id }) { row ->
                            MessageRow(row, identity, people, isGroup, showsStatus = row.id == lastOwn,
                                seenLine = if (row.id == seen?.id) seenLine else null, times = times, actions = actions, showReactions = { reactionsOf = it },
                                showPerson = { person = it })
                        }
                        item("top") { Top(room, identity, messages.isEmpty()) }
                    }
                    FloatingDay(list, rows, times, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                    androidx.compose.animation.AnimatedVisibility(showsJump, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp),
                        enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
                        JumpButton(Timeline.newCount(messages, jumpSince ?: Long.MAX_VALUE)) {
                            holdsLine = false
                            jumpSince = null
                            scope.launch { list.animateScrollToItem(0) }
                        }
                    }
                } }
            }
        }
    }
    reactionsOf?.let { message ->
        ModalBottomSheet({ reactionsOf = null }, containerColor = MaterialTheme.colorScheme.background) { ReactionList(message, people) }
    }
    if (info) ChatInfoScreen(room) { info = false }
    person?.let { user -> PersonCard(people.name(user), profiles[user]?.handle, people.avatar(user)) { person = null } }
    viewer?.let { MediaViewer(it) { viewer = null } }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete request from ${room.name}?") },
            // ponytail: iOS's copy; whether Instagram shows the requester a "Seen" is untested, so it keeps the warning.
            text = { Text("It's deleted from ${login?.profile?.username?.let { "@$it's" } ?: "your"} Instagram inbox too. They may see it as read.") },
            confirmButton = {
                TextButton({ confirmingDelete = false; AppModel.deleteRequest(room); back() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * iOS 1.5.1: holding a bubble or card brings its sheet up after 0.3 s, as the mic's hold (Android's default is 0.4 s,
 * older ones' 0.5 s). A longer delay chosen in Android's accessibility settings stays.
 */
@Composable
private fun quickHold(system: ViewConfiguration): ViewConfiguration = remember(system) {
    if (system.longPressTimeoutMillis > 500) system else object : ViewConfiguration by system {
        override val longPressTimeoutMillis: Long get() = 300
    }
}

/** DESIGN T1's ⋯ menu, its v1 part: Chat Info, Open in Instagram (once the handle is known) and Archive or Unarchive. */
@Composable
private fun MoreMenu(room: Room, openInfo: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val folder = Inbox.folder(room)
    val archived = folder == Inbox.Folder.ARCHIVED
    Box {
        IconButton({ open = true }) { Icon(Icons.more, "More") }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("Chat Info") }, { open = false; openInfo() }, leadingIcon = { Icon(Icons.info, null) })
            AppModel.contactHandle(room)?.let { handle ->
                DropdownMenuItem({ Text("Open in Instagram") }, { open = false; openProfile(context, handle) },
                    leadingIcon = { Icon(Icons.openOutside, null) })
            }
            if (folder != Inbox.Folder.REQUESTS) { // requests stay in Requests
                DropdownMenuItem({ Text(if (archived) "Unarchive" else "Archive") }, { open = false; AppModel.setArchived(!archived, room) },
                    leadingIcon = { Icon(if (archived) Icons.unarchive else Icons.archive, null) })
            }
        }
    }
}

/** Who someone is in this chat: the thread's names (`Room.displayName`), first names for captions, pictures. */
private class People(val room: Room, val profiles: Map<String, UserProfile>) {
    fun name(user: String) = room.displayName(user, profiles[user])
    fun avatar(user: String) = room.avatarURL(user, profiles[user])
    /** "You", or the first word of their name: "You replied to Kivo", "Kivo unsent a message". */
    fun firstName(user: String) = if (user == AppModel.ME) "You" else name(user).split(' ').firstOrNull { it.isNotEmpty() } ?: name(user)
}

/** One glass-free capsule's worth: back, the contact, and under the name the account this chat belongs to. A tap: Chat Info. */
@Composable
private fun ThreadHeader(room: Room, identity: AccountIdentity?, back: () -> Unit, openInfo: () -> Unit, actions: @Composable () -> Unit) {
    // As iOS: back and ⋯ in round buttons, the contact and the account's tag in a capsule between them.
    Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp)) {
        Box(Modifier.align(Alignment.CenterStart).size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
            .clickable(onClickLabel = "Back", onClick = back), contentAlignment = Alignment.Center) { Icon(Icons.back, "Back") }
        Row(
            Modifier.align(Alignment.Center).padding(horizontal = 56.dp).heightIn(min = 48.dp).shadow(4.dp, CircleShape).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface).clickable(onClickLabel = "Show chat info", onClick = openInfo)
                .padding(start = 6.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)
                .semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatAvatar(room, 32.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(room.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (identity != null) AccountTag(identity, compact = true)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center) { actions() }
    }
}

/** The top of the history: the start block (who it's with, the account) once it's all here, else a spinner that pages back. */
@Composable
private fun Top(room: Room, identity: AccountIdentity?, isEmpty: Boolean) {
    if (room.reachedStart) {
        val block = Inbox.startBlock(room.bridge?.isDM == false, Inbox.memberCount(room), AppModel.contactHandle(room), identity?.name, isEmpty)
        Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 20.dp).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChatAvatar(room, 64.dp)
            Spacer(Modifier.height(2.dp))
            Text(room.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            block.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (identity != null) AccountTag(identity, Modifier.padding(top = 6.dp))
            block.messagingFrom?.let { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    } else {
        // Composed only while on screen: it pages back until the screen is full or the history ends.
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LaunchedEffect(room.prevBatch) { AppModel.loadOlder(room) }
    }
}

@Composable
private fun MessageRow(
    row: Timeline.Row,
    identity: AccountIdentity?,
    people: People,
    isGroup: Boolean,
    showsStatus: Boolean,
    seenLine: String?,
    times: ThreadTimes,
    actions: MessageActions,
    showReactions: (Message) -> Unit,
    showPerson: (String) -> Unit,
) {
    val message = row.message
    val own = message.isOwn
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = row.spacing.dp)) {
        if (row.startsDay) DayChip(times.day(message.ts)) // above the line, as iOS
        if (row.startsUnread) UnreadLine()
        if (message.kind.isChatEvent) {
            // Instagram's own lines and what the app can't show: small centred text across the thread.
            val text = (message.kind as? MessageKind.Unsupported)?.let { "${people.firstName(message.sender)} sent ${it.what}. Open Instagram to see it." } ?: message.body
            Text(text, Modifier.fillMaxWidth().padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = secondary, textAlign = TextAlign.Center)
            return@Column
        }
        val indent = if (row.groupSender == null) 0.dp else GROUP_INDENT
        val clock = times.clock(message.ts)
        // TalkBack (iOS's `spoken`): one description for the bubble, the sender, the text and the time; a card says what
        // it is and its time itself, after the sender. The links in the text are its actions.
        val context = LocalContext.current
        val words = message.body.takeIf { message.shareText == null && message.kind in TEXT_KINDS }
        val label = when {
            message.kind == MessageKind.Unsent -> null
            words != null -> Timeline.spokenLabel(row, words, clock, identity?.name, people.name(message.sender))
            else -> Timeline.spokenSender(row, identity?.name, people.name(message.sender))
        }
        // iOS 1.7's swipe to reply, where the sheet's Reply is (not in a request, on a tombstone or a send not echoed).
        val canReply = !actions.readOnly && message.eventID != null && message.kind != MessageKind.Unsent
        val extraActions = listOfNotNull(CustomAccessibilityAction("Reply") { actions.reply(message); true }.takeIf { canReply }) +
            Links.find(message.shareText ?: words.orEmpty()).map { link ->
                CustomAccessibilityAction("Open ${parseURL(link.url)?.host ?: link.url}") { openLink(context, link.url); true }
            }
        Column(Modifier.fillMaxWidth().swipeToReply(canReply) { actions.reply(message) }, horizontalAlignment = if (own) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (row.groupSender?.showsName == true && message.reply == null) {
                // Neutral, never coloured: colour means account. The bubble says it to TalkBack.
                Text(people.name(message.sender), Modifier.padding(start = indent + 12.dp).clearAndSetSemantics {}, style = MaterialTheme.typography.labelMedium,
                    color = secondary, maxLines = 1)
            }
            message.reply?.let { Quote(it, message, row, people, Modifier.padding(start = indent)) }
            ShareCaption.of(message.content).eyebrow?.takeIf { message.kind != MessageKind.Unsent }?.let {
                Text(it, Modifier.padding(start = indent + 4.dp, end = 4.dp), style = MaterialTheme.typography.labelMedium, color = secondary)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                row.groupSender?.let { sender ->
                    Box(Modifier.size(GROUP_AVATAR).offset(y = if (message.reactions.isEmpty()) 0.dp else -PILL_DROP)) {
                        if (sender.showsAvatar) Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Show who they are") { showPerson(message.sender) }
                            .semantics { contentDescription = people.name(message.sender) }) {
                            Picture(people.avatar(message.sender), people.name(message.sender), GROUP_AVATAR)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box(Modifier.fillMaxWidth(0.75f), contentAlignment = if (own) Alignment.BottomEnd else Alignment.BottomStart) {
                    Box(Modifier.padding(bottom = if (message.reactions.isEmpty()) 0.dp else PILL_DROP)) {
                        MessageMenu(message, actions, label, extraActions) {
                            Bubble(message, row.position, identity, people, clock.takeIf { message.kind != MessageKind.Unsent }) {
                                if (message.reactions.isNotEmpty()) ReactionPills(message, people, isGroup) { showReactions(message) }
                            }
                        }
                    }
                }
            }
            if (message.isEdited && message.kind != MessageKind.Unsent) {
                Text("Edited", Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall, color = secondary)
            }
            when {
                message.status is Message.Status.Failed -> NotSent { actions.retry(message) }
                showsStatus && message.kind != MessageKind.Unsent -> Text(
                    seenLine ?: when (message.status) {
                        Message.Status.Sending -> "Sending…"
                        else -> if (message.isSeen) "Seen" else "Sent"
                    },
                    Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall, color = secondary,
                )
                seenLine != null -> Text(seenLine, Modifier.fillMaxWidth().padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall,
                    color = secondary, textAlign = TextAlign.End)
            }
        }
    }
}

/** How far a message is swiped before letting go replies to it (iOS 1.7). */
private val REPLY_THRESHOLD = 60.dp

/**
 * iOS 1.7's swipe to reply, toward the end (right; left in RTL, decision D3): the message follows the finger, past
 * 60 dp at a third of its pace, with the reply arrow fading in from the start side and growing once letting go would
 * reply (a tick there). It springs back either way. Decision A14: it's a swipe only once the finger has moved a touch
 * slop along one axis, mostly toward the end (twice as far across as down); until then it watches after the bubble's
 * own handlers and before the list (the Main pass, inner first), so the voice bars' seek and the list's scroll take
 * what's theirs, and a hold that opened the sheet stays a hold. Once it's a swipe it takes the moves first (the Initial
 * pass), which cancels the bubble's tap and hold. Measured on this unmoving node, the row moving under it.
 */
@Composable
private fun Modifier.swipeToReply(enabled: Boolean, reply: () -> Unit): Modifier {
    if (!enabled) return this
    val offset = remember { Animatable(0f) }
    val grown = remember { Animatable(0.7f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1f else -1f
    val threshold = with(LocalDensity.current) { REPLY_THRESHOLD.toPx() }
    val arrow = rememberVectorPainter(Icons.reply)
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val replying by rememberUpdatedState(reply)
    LaunchedEffect(threshold) {
        snapshotFlow { offset.value >= threshold }.collectLatest { past ->
            if (past) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
            grown.animateTo(if (past) 1f else 0.7f, tween(150))
        }
    }
    return pointerInput(dir, threshold) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false) // the bubble's tap handlers take the down
            var moved = Offset.Zero
            var claimed = false
            try {
                while (true) {
                    val change = awaitPointerEvent(if (claimed) PointerEventPass.Initial else PointerEventPass.Main)
                        .changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (claimed && offset.value >= threshold) replying()
                        break
                    }
                    if (!claimed) {
                        if (change.isConsumed || change.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) break
                        moved += change.positionChange()
                        if (max(abs(moved.x), abs(moved.y)) < viewConfiguration.touchSlop) continue
                        if (dir * moved.x > 0 && abs(moved.x) > 2 * abs(moved.y)) claimed = true else break
                    }
                    change.consume()
                    val x = max(0f, dir * (change.position.x - down.position.x))
                    scope.launch { offset.snapTo(if (x <= threshold) x else threshold + (x - threshold) / 3) }
                }
            } finally {
                if (claimed) scope.launch { if (animationsOff(context)) offset.snapTo(0f) else offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
            }
        }
    }.drawWithContent {
        drawContent()
        val o = offset.value
        if (o <= 0f) return@drawWithContent
        // The arrow at the start side, 40 dp behind the message's edge (iOS's offset ± 40), clear by the threshold.
        val side = 24.dp.toPx()
        val x = (o - 40.dp.toPx()).let { if (dir > 0) it else size.width - it - side }
        translate(x, (size.height - side) / 2) {
            scale(grown.value, Offset(side / 2, side / 2)) {
                with(arrow) { draw(Size(side, side), alpha = min(1f, o / threshold), colorFilter = ColorFilter.tint(tint)) }
            }
        }
    }.graphicsLayer { translationX = dir * offset.value }
}

/**
 * iOS 1.5's jump to the newest message: a chevron, and how many others sent since it appeared. Never the account's
 * colour or a red badge.
 */
@Composable
private fun JumpButton(count: Int, jump: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).shadow(4.dp, CircleShape).clip(CircleShape)
            .background(Bubbles.raised).clickable(onClick = jump)
            .semantics(mergeDescendants = true) {
                contentDescription = "Jump to newest message"
                if (count > 0) stateDescription = if (count == 1) "1 new message" else "$count new messages"
            }
            .padding(horizontal = if (count > 0) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.chevronDown, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        if (count > 0) Text(if (count > 99) "99+" else "$count", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** iOS 1.7: a day's start ("Today", "Mon 3 Nov") above its first row; a heading, so TalkBack can go day by day. */
@Composable
private fun DayChip(text: String) {
    Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) { DayCapsule(text, Modifier.semantics { heading() }) }
}

/** The day chip's capsule (decisions A15, A38): raised, with a light shadow, as the jump button; never a bubble's grey. */
@Composable
private fun DayCapsule(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.shadow(2.dp, CircleShape).clip(CircleShape).background(Bubbles.raised).padding(horizontal = 12.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

/** The top row's day on the thread while the user scrolls it (iOS 1.7). */
private data class Day(val date: LocalDate, val chipped: Boolean)

/**
 * iOS 1.7: while the user scrolls, the top row's day floats under the header, unless that row has its own chip; it
 * goes a second after the scroll stops (decision A27). The jump button's and following the end's scrolls make no drag,
 * so they never show it. Only this reads the list's layout, and only as the day: the thread around it isn't
 * recomposed each frame (the composer's trap). Hidden from TalkBack (the chips say the day) and from touches.
 */
@Composable
private fun FloatingDay(list: LazyListState, rows: List<Timeline.Row>, times: ThreadTimes, modifier: Modifier) {
    val byID by rememberUpdatedState(remember(rows) { rows.associateBy { it.id } })
    val zone = ZoneId.systemDefault()
    val top by remember(list) {
        derivedStateOf {
            // Reversed: the highest index on screen is the top row; the spinner or start block and typing aren't rows.
            val id = list.layoutInfo.visibleItemsInfo.lastOrNull { it.key != "top" && it.key != "typing" }?.key as? String
            id?.let { byID[it] }?.let { Day(Instant.ofEpochMilli(it.message.ts).atZone(zone).toLocalDate(), it.startsDay) }
        }
    }
    var dragged by remember(list) { mutableStateOf(false) }
    LaunchedEffect(list) { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true } }
    LaunchedEffect(list) { snapshotFlow { list.isScrollInProgress }.collectLatest { if (!it) { delay(1_000); dragged = false } } }
    val day = top
    androidx.compose.animation.AnimatedVisibility(dragged && day != null && !day.chipped, modifier.clearAndSetSemantics {},
        enter = fadeIn(tween(200, easing = EaseOut)), exit = fadeOut(tween(200, easing = EaseOut))) {
        day?.let { DayCapsule(times.day(it.date.atStartOfDay(zone).toInstant().toEpochMilli())) }
    }
}

/** Where what you hadn't read starts, when the chat opened unread. */
@Composable
private fun UnreadLine() {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text("New messages", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * A message's bubble; a story reply or reaction is the story with what was sent under it, as Instagram shows them.
 * `pills` and the time (iOS 1.7, `time`: none on a tombstone) sit on the bubble's last part: the text under a story or
 * a share, not the card's far edge.
 */
@Composable
private fun Bubble(message: Message, position: GroupPosition, identity: AccountIdentity?, people: People, time: String?, pills: @Composable () -> Unit) {
    val own = message.isOwn
    val shape = bubbleShape(position, own)
    val fill = if (own) identity?.color?.fillColor ?: MaterialTheme.colorScheme.onBackground else Bubbles.incoming
    val text = if (own) Color.White else MaterialTheme.colorScheme.onBackground
    val share = message.shareText
    Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (share == null) PillLayout(own, pills) { BubbleBody(message, shape, fill, text, people, time) }
        else BubbleBody(message, shape, fill, text, people, time = null)
        share?.let {
            // Its words go after the card's (the bubble's description names only the sender).
            PillLayout(own, pills) { TextBubble(it, bubbleShape(GroupPosition.SINGLE, own), fill, text, own, time.orEmpty(), spoken = listOfNotNull(it, time).joinToString(", ")) }
        }
    }
}

/**
 * The bubble with its reaction pills on the bottom edge (iOS 1.6.1's `PillPlacement`, decision A26): the pills at their
 * own size, never offered the bubble's width (iOS's squeeze) and never widening it (a Box did: our short bubble slid
 * out of line), 8 dp past its inner corner; too wide, they grow inward from 6 dp inside the outer edge, level with
 * "Seen". The bubble's size is the layout's; the pills hang `PILL_DROP` below it, outside.
 */
@Composable
private fun PillLayout(own: Boolean, pills: @Composable () -> Unit, bubble: @Composable () -> Unit) {
    Layout(contents = listOf({ Box { bubble() } }, pills)) { (bubbles, pill), constraints ->
        val body = bubbles.first().measure(constraints)
        val p = pill.firstOrNull()?.measure(Constraints())
        layout(body.width, body.height) {
            body.place(0, 0)
            if (p != null) {
                val x = if (own) minOf(-8.dp.roundToPx(), body.width - 6.dp.roundToPx() - p.width) else maxOf(body.width + 8.dp.roundToPx() - p.width, 6.dp.roundToPx())
                p.place(x, body.height + PILL_DROP.roundToPx() - p.height)
            }
        }
    }
}

@Composable
private fun BubbleBody(message: Message, shape: RoundedCornerShape, fill: Color, text: Color, people: People, time: String?) {
    val own = message.isOwn
    when (val kind = message.kind) {
        MessageKind.Unsent -> {
            val stroke = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                if (own) "You unsent a message" else "${people.firstName(message.sender)} unsent a message",
                Modifier.drawBehind {
                    drawRoundRect(stroke, cornerRadius = CornerRadius(size.height / 2),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                }.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MessageKind.Text, MessageKind.Notice -> TextBubble(message.body, shape, fill, text, own, time.orEmpty())
        MessageKind.Link -> LinkBubble(message, shape, fill, text, time.orEmpty())
        // ponytail: a labelled card until A6 shows the media itself (photos, videos, voice, GIFs, shares).
        else -> MediaBubble(message, kind, time) { MediaPlaceholder(kind, message, shape, fill, text, it) }
    }
}

/** The kinds whose bubble is their text, which TalkBack reads with the sender and the time. */
private val TEXT_KINDS = setOf(MessageKind.Text, MessageKind.Notice, MessageKind.Link)

/** How far the reaction pills hang below the bubble (iOS 1.7): low enough to leave its time readable. */
private val PILL_DROP = 22.dp

/**
 * A message's text with its web links (iOS 1.6.4, `Links.find`) underlined in the text's own colour (colour means
 * account). With links the text takes its own touches (decision A34, the plan's fallback B): a tap on a link opens it,
 * elsewhere it's the bubble's tap; a hold and a double tap stay the bubble's (its sheet, a heart). Compose's
 * `LinkAnnotation` took the whole press, so a hold on a link opened the link and never the sheet (seen on the
 * emulator); iOS too attaches its hold only with links. `time`: a clear copy of the bubble's time after the last word,
 * the room the shown one sits in.
 */
@Composable
private fun LinkedText(body: String, time: String?, modifier: Modifier, color: Color) {
    val links = remember(body) { Links.find(body) }
    val small = MaterialTheme.typography.labelSmall.fontSize
    val text = remember(body, time, small) {
        buildAnnotatedString {
            append(body)
            for (link in links) addStyle(SpanStyle(textDecoration = TextDecoration.Underline), link.range.first, link.range.last + 1)
            if (time != null) withStyle(SpanStyle(color = Color.Transparent, fontSize = small, fontFeatureSettings = "tnum")) { append("  $time") }
        }
    }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap = LocalTap.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val touches = if (links.isEmpty() || tap == null) Modifier else Modifier.pointerInput(links) {
        fun linkAt(at: Offset): Links.Link? {
            val l = layout.value ?: return null
            // A cursor position: the right half of a letter answers the offset after it. The letter is the one on
            // either side whose box holds the touch (none past a line's end).
            val i = l.getOffsetForPosition(at)
            val letter = listOf(i - 1, i).firstOrNull { it in body.indices && l.getBoundingBox(it).contains(at) } ?: return null
            return links.firstOrNull { letter in it.range }
        }
        detectTapGestures(
            onDoubleTap = { tap.heart?.invoke() },
            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); tap.hold?.invoke() },
            onTap = { at -> linkAt(at)?.let { openLink(context, it.url) } ?: tap.click() },
        )
    }
    Text(text, modifier.then(touches), style = MaterialTheme.typography.bodyLarge, color = color, onTextLayout = { layout.value = it })
}

/** A web link, in the phone's browser; a phone without one says so instead of crashing. */
private fun openLink(context: android.content.Context, url: String) {
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } catch (_: android.content.ActivityNotFoundException) {
        AppModel.actionError = "No app on this phone opens links."
    }
}

/**
 * A text with the engine's link preview under it (as iOS, DESIGN §13): its picture (240 wide, between 0.4 and 1 of
 * that tall), title and site. The preview is the link: a tap opens it, a hold is the message's.
 */
@Composable
private fun LinkBubble(message: Message, shape: RoundedCornerShape, fill: Color, text: Color, time: String) {
    val preview = LinkPreview.of(message.content) ?: return TextBubble(message.body, shape, fill, text, message.isOwn, time)
    val context = LocalContext.current
    Box(Modifier.width(240.dp).clip(shape).background(fill)) {
        Column {
            LinkedText(message.body, null, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).clearAndSetSemantics {}, text)
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.06f)).ownTap { openLink(context, preview.url) }) {
                preview.imageURL?.let { url ->
                    val px = with(LocalDensity.current) { 240.dp.roundToPx() }
                    Box(Modifier.fillMaxWidth().height(240.dp * (preview.aspect ?: 0.525).coerceIn(0.4, 1.0).toFloat())
                        .background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Pictures.load(url, px)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
                // The time beside the title and the site, never over them (at any font size).
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        preview.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = text, maxLines = 2) }
                        Text(parseURL(preview.url)?.host ?: preview.url, style = MaterialTheme.typography.labelMedium, color = text.copy(alpha = 0.75f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    CardTime(time, Modifier, spoken = false)
                }
            }
        }
    }
}

/**
 * A text in its bubble, its links underlined, and its time in the corner (iOS 1.7, decision A17): a clear copy of the
 * time ends the text, so the shown one sits on the last line when it fits, else on a line of its own (WhatsApp's). Both
 * texts are hidden from TalkBack, which reads the bubble's description instead (`spoken`, or `MessageMenu`'s label):
 * the clear copy would read the time twice. Emoji alone: no bubble, the time beside them.
 */
@Composable
private fun TextBubble(body: String, shape: RoundedCornerShape, fill: Color, text: Color, own: Boolean, time: String, spoken: String? = null) {
    val words = Modifier.clearAndSetSemantics { spoken?.let { contentDescription = it } }
    val clock = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    if (isEmojiOnly(body)) {
        Row(words, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val beside = @Composable { Text(time, Modifier.alignByBaseline(), style = clock, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (own) beside()
            Text(body, Modifier.alignByBaseline(), fontSize = 48.sp) // DESIGN §6.1: no bubble
            if (!own) beside()
        }
    } else {
        Box(Modifier.background(fill, shape).then(words)) {
            LinkedText(body, time, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), text)
            Text(time, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 6.dp), style = clock,
                color = if (own) Color.White.copy(alpha = 0.8f) else Bubbles.timeOnIncoming)
        }
    }
}

@Composable
private fun MediaPlaceholder(kind: MessageKind, message: Message, shape: RoundedCornerShape, fill: Color, text: Color, time: String?) {
    val media = kind.media ?: return
    val author = (kind as? MessageKind.Share)?.let { ShareCaption.of(message.content).author }
    Row(Modifier.background(fill, shape).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.of(media.glyph), null, Modifier.size(20.dp), tint = text)
        Text(media.label + (author?.let { " · @$it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = text)
        // The time after the label, as in a text bubble (a capsule would cover the label).
        time?.let { Text(it, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = if (message.isOwn) Color.White.copy(alpha = 0.8f) else Bubbles.timeOnIncoming) }
    }
}

/** DESIGN §6.1: radius 20; the corners on the sender's side shrink to 6 where the bubble joins its neighbour. */
private fun bubbleShape(position: GroupPosition, own: Boolean): RoundedCornerShape {
    val top = if (position == GroupPosition.MIDDLE || position == GroupPosition.LAST) 6.dp else 20.dp
    val bottom = if (position == GroupPosition.FIRST || position == GroupPosition.MIDDLE) 6.dp else 20.dp
    return if (own) RoundedCornerShape(topStart = 20.dp, topEnd = top, bottomEnd = bottom, bottomStart = 20.dp)
    else RoundedCornerShape(topStart = top, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = bottom)
}

/** 1–3 emoji and nothing else. */
private fun isEmojiOnly(text: String): Boolean {
    val graphemes = java.text.BreakIterator.getCharacterInstance().run {
        setText(text)
        generateSequence(first()) { next().takeIf { it != java.text.BreakIterator.DONE } }.zipWithNext { a, b -> text.substring(a, b) }.toList()
    }
    return graphemes.size in 1..3 && graphemes.all { g ->
        val first = g.codePointAt(0)
        UCharacter.hasBinaryProperty(first, UProperty.EMOJI_PRESENTATION) ||
            g.codePointCount(0, g.length) > 1 && g.codePoints().anyMatch { UCharacter.hasBinaryProperty(it, UProperty.EMOJI) && it > 0xFF }
    }
}

/** "You replied to Kivo" and a faded outline of the original (DESIGN §6.2). */
@Composable
private fun Quote(reply: Message.Reply, message: Message, row: Timeline.Row, people: People, modifier: Modifier) {
    val replied = when (reply.sender) {
        null -> "replied"
        message.sender -> if (message.isOwn) "replied to yourself" else "replied to themselves"
        AppModel.ME -> "replied to you"
        else -> "replied to ${people.firstName(reply.sender)}"
    }
    // Where a group's run starts this is its sender label too, so the full name: "Sam Lee replied to Theo".
    val replier = if (message.isOwn) "You" else if (row.groupSender?.showsName == true) people.name(message.sender) else people.firstName(message.sender)
    val snippet = when {
        !reply.isLoaded -> "Loading…"
        reply.kind == MessageKind.Unsent -> "Unsent message"
        else -> reply.kind?.media?.label ?: reply.body
    }
    Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = if (message.isOwn) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("$replier $replied", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(snippet, Modifier.widthIn(max = 280.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Emoji + count in a DM; in a group each emoji with up to three faces (DESIGN §6.2). Opens who reacted. */
@Composable
private fun ReactionPills(message: Message, people: People, isGroup: Boolean, show: () -> Unit) {
    val spoken = "Reactions: " + message.reactions.joinToString(", ") { r ->
        if (isGroup) "${r.key} from ${r.senders.joinToString(" and ") { if (it == AppModel.ME) "you" else people.name(it) }}"
        else if (r.count > 1) "${r.key} ${r.count}" else r.key
    }
    Row(
        Modifier.shadow(2.dp, CircleShape).border(2.dp, MaterialTheme.colorScheme.background, CircleShape).background(Bubbles.reaction, CircleShape)
            .clickable(onClick = show).heightIn(min = 24.dp).padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        // iOS 1.6.1's spacing: 10 between a group's emoji, 1 between an emoji and its faces.
        horizontalArrangement = Arrangement.spacedBy(if (isGroup) 10.dp else 2.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        for (r in message.reactions) {
            Row(horizontalArrangement = Arrangement.spacedBy(if (isGroup) 1.dp else 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.key, fontSize = 14.sp)
                if (isGroup) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-5).dp)) {
                        for (user in r.senders.take(3)) Picture(people.avatar(user), people.name(user), 16.dp)
                    }
                    if (r.count > 3) Count("+${r.count - 3}")
                } else if (r.count > 1) Count("${r.count}")
            }
        }
    }
}

@Composable
private fun Count(text: String) = Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Who reacted with what, as Instagram lists them. */
@Composable
private fun ReactionList(message: Message, people: People) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("Reactions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        for (r in message.reactions) for (user in r.senders) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Picture(people.avatar(user), people.name(user), 36.dp)
                Spacer(Modifier.width(12.dp))
                Text(if (user == AppModel.ME) "You" else people.name(user), Modifier.weight(1f), maxLines = 1)
                Text(r.key, fontSize = 24.sp)
            }
        }
    }
}

/** DESIGN T1: an incoming bubble with three dots. */
@Composable
private fun TypingBubble(modifier: Modifier) {
    Box(modifier.fillMaxWidth()) {
        Text("•••", Modifier.background(Bubbles.incoming, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = "Typing" },
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = FontStyle.Normal)
    }
}

/** The thread's own colours (DESIGN §8.1): the incoming bubble, the reaction pills, the time on an incoming bubble. */
object Bubbles {
    val incoming: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF262629) else Color(0xFFE9E9EB)
    val reaction: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2C2C2E) else Color.White
    /**
     * What floats over the thread (a day chip, the jump button): the surface in light; in dark a grey lighter than an
     * incoming bubble, since neither black nor a shadow shows on black (decision A38). Never an account's colour.
     */
    val raised: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF3A3A3C) else Color.White
    /** iOS's `inkSecondaryOnBubble`. */
    val timeOnIncoming: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFA1A1A8) else Color(0xFF5E5E64)
}

/** A bubble's time ("9:41", decision A16: the phone's 12/24-hour setting) and a day chip's words, in the phone's formats. */
private class ThreadTimes(private val clock: DateTimeFormatter, private val days: Timeline.DayFormats) {
    fun clock(ts: Long): String = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(clock)
    fun day(ts: Long) = Timeline.dayLabel(ts, System.currentTimeMillis(), ZoneId.systemDefault(), days)
}

@Composable
private fun rememberThreadTimes(): ThreadTimes {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val h24 = DateFormat.is24HourFormat(context)
    return remember(locale, h24) {
        fun best(skeleton: String) = DateFormat.getBestDateTimePattern(locale, skeleton)
        ThreadTimes(DateTimeFormatter.ofPattern(best(if (h24) "Hm" else "hm"), locale),
            Timeline.DayFormats(best("EEEE"), best("EEEdMMM"), best("EEEdMMMy"), locale))
    }
}
