package fi.ferry.messenger.ui

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var attachment by remember(roomID) { mutableStateOf<AttachmentState?>(null) }
    val context = LocalContext.current
    // Android's photo picker: no permission, only what's picked (DESIGN §6.9: photos and videos, no camera).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val preparing = AttachmentState.Preparing(if (context.contentResolver.getType(uri)?.startsWith("video/") == true) "video" else "photo")
        attachment = preparing
        Outgoing.prepare(context, uri) { result ->
            if (attachment !== preparing) return@prepare // removed meanwhile
            attachment = result.getOrNull()?.let { AttachmentState.Ready(it) }
            result.exceptionOrNull()?.let { AppModel.actionError = it.message ?: "This can't be sent." }
        }
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
        val media = (attachment as? AttachmentState.Ready)?.attachment
        if (text.isEmpty() && media == null || text.length > Timeline.MAX_TEXT_LENGTH) return
        val edited = editing
        if (edited != null) {
            if (text.isNotEmpty() && text != edited.body) AppModel.edit(edited, text, room)
        } else {
            // The media first, then the text as its own message (Instagram drops captions); the reply goes on the text.
            if (media != null) {
                AppModel.sendMedia(media, replyingTo?.eventID?.takeIf { text.isEmpty() }, room)
                attachment = null
            }
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
    // at most). A touch, the jump button or a send lets go.
    val lineRow = remember(roomID) { rows.indexOfFirst { it.startsUnread } }
    var holdsLine by remember(roomID) { mutableStateOf(unreadAfter != null && lineRow >= 0) }
    var revealed by remember(roomID) { mutableStateOf(!holdsLine) }
    LaunchedEffect(roomID) {
        if (!holdsLine) return@LaunchedEffect
        launch { delay(1_000); revealed = true }
        snapshotFlow { list.layoutInfo.viewportSize.height }.first { it > 0 }
        val line = rows.size - 1 - lineRow + if (typing) 1 else 0 // reversed: index 0 is the newest, at the bottom
        list.scrollToItem(line) // the line's row near the bottom (higher up when the chat's start is close)
        val info = list.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val lineItem = info.visibleItemsInfo.firstOrNull { it.index == line }
        val context = if (lineRow > 0) info.visibleItemsInfo.firstOrNull { it.index == line + 1 }?.size ?: 0 else 0
        // Reversed, offsets count up from the bottom: the line's top edge goes to the top, less the last message read.
        if (lineItem != null) list.scrollBy(-(info.viewportEndOffset - context - (lineItem.offset + lineItem.size)).toFloat())
        val below = -list.scrollBy(-1e7f) // what's under the screen there (it ends at the end)
        if (below - context > viewport / 4) list.scrollBy(below) else holdsLine = false
        revealed = true
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
                    attachment = attachment,
                    attach = if (bar == Inbox.ThreadBar.COMPOSER && editing == null) {
                        { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                    } else null,
                    removeAttachment = { attachment = null },
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
        if (row.startsUnread) UnreadLine()
        if (row.showsTime) {
            Text(times.label(message.ts), Modifier.fillMaxWidth().padding(bottom = 6.dp), style = MaterialTheme.typography.labelMedium,
                color = secondary, textAlign = TextAlign.Center)
        }
        if (message.kind.isChatEvent) {
            // Instagram's own lines and what the app can't show: small centred text across the thread.
            val text = (message.kind as? MessageKind.Unsupported)?.let { "${people.firstName(message.sender)} sent ${it.what}. Open Instagram to see it." } ?: message.body
            Text(text, Modifier.fillMaxWidth().padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = secondary, textAlign = TextAlign.Center)
            return@Column
        }
        val indent = if (row.groupSender == null) 0.dp else GROUP_INDENT
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (own) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (row.groupSender?.showsName == true && message.reply == null) {
                // Neutral, never coloured: colour means account.
                Text(people.name(message.sender), Modifier.padding(start = indent + 12.dp), style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = 1)
            }
            message.reply?.let { Quote(it, message, row, people, Modifier.padding(start = indent)) }
            ShareCaption.of(message.content).eyebrow?.takeIf { message.kind != MessageKind.Unsent }?.let {
                Text(it, Modifier.padding(start = indent + 4.dp, end = 4.dp), style = MaterialTheme.typography.labelMedium, color = secondary)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                row.groupSender?.let { sender ->
                    Box(Modifier.size(GROUP_AVATAR).offset(y = if (message.reactions.isEmpty()) 0.dp else (-14).dp)) {
                        if (sender.showsAvatar) Box(Modifier.clip(CircleShape).clickable(onClickLabel = "Show who they are") { showPerson(message.sender) }
                            .semantics { contentDescription = people.name(message.sender) }) {
                            Picture(people.avatar(message.sender), people.name(message.sender), GROUP_AVATAR)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box(Modifier.fillMaxWidth(0.75f), contentAlignment = if (own) Alignment.BottomEnd else Alignment.BottomStart) {
                    Box(Modifier.padding(bottom = if (message.reactions.isEmpty()) 0.dp else 14.dp)) {
                        MessageMenu(message, actions) {
                            Bubble(message, row.position, identity, people) {
                                if (message.reactions.isNotEmpty()) {
                                    ReactionPills(message, people, isGroup, Modifier.align(if (own) Alignment.BottomStart else Alignment.BottomEnd)
                                        .offset(x = if (own) (-8).dp else 8.dp, y = 14.dp)) { showReactions(message) }
                                }
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

/**
 * iOS 1.5's jump to the newest message: a chevron, and how many others sent since it appeared. Never the account's
 * colour or a red badge.
 */
@Composable
private fun JumpButton(count: Int, jump: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).shadow(4.dp, CircleShape).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = jump)
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
 * `pills` sit on the bubble's last part: the text under a story or a share, not the card's far edge.
 */
@Composable
private fun Bubble(message: Message, position: GroupPosition, identity: AccountIdentity?, people: People, pills: @Composable BoxScope.() -> Unit) {
    val own = message.isOwn
    val shape = bubbleShape(position, own)
    val fill = if (own) identity?.color?.fillColor ?: MaterialTheme.colorScheme.onBackground else Bubbles.incoming
    val text = if (own) Color.White else MaterialTheme.colorScheme.onBackground
    val share = message.shareText
    Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box { BubbleBody(message, shape, fill, text, people); if (share == null) pills() }
        share?.let { Box { TextBubble(it, bubbleShape(GroupPosition.SINGLE, own), fill, text); pills() } }
    }
}

@Composable
private fun BubbleBody(message: Message, shape: RoundedCornerShape, fill: Color, text: Color, people: People) {
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
        MessageKind.Text, MessageKind.Notice -> TextBubble(message.body, shape, fill, text)
        MessageKind.Link -> LinkBubble(message, shape, fill, text)
        // ponytail: a labelled card until A6 shows the media itself (photos, videos, voice, GIFs, shares).
        else -> MediaBubble(message, kind) { MediaPlaceholder(kind, message, shape, fill, text) }
    }
}

/**
 * A text with the engine's link preview under it (as iOS, DESIGN §13): its picture (240 wide, between 0.4 and 1 of
 * that tall), title and site. The preview is the link: a tap opens it, a hold is the message's.
 */
@Composable
private fun LinkBubble(message: Message, shape: RoundedCornerShape, fill: Color, text: Color) {
    val preview = LinkPreview.of(message.content)
    val context = LocalContext.current
    Column(Modifier.then(if (preview != null) Modifier.width(240.dp) else Modifier).clip(shape).background(fill)) {
        Text(message.body, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge, color = text)
        if (preview == null) return@Column
        Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.06f))
            .ownTap { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(preview.url))) }) {
            preview.imageURL?.let { url ->
                val px = with(LocalDensity.current) { 240.dp.roundToPx() }
                Box(Modifier.fillMaxWidth().height(240.dp * (preview.aspect ?: 0.525).coerceIn(0.4, 1.0).toFloat())
                    .background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Pictures.load(url, px)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                preview.title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = text, maxLines = 2) }
                Text(parseURL(preview.url)?.host ?: preview.url, style = MaterialTheme.typography.labelMedium, color = text.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun TextBubble(body: String, shape: RoundedCornerShape, fill: Color, text: Color) {
    if (isEmojiOnly(body)) {
        Text(body, fontSize = 48.sp) // DESIGN §6.1: no bubble
    } else {
        Text(body, Modifier.background(fill, shape).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge, color = text)
    }
}

@Composable
private fun MediaPlaceholder(kind: MessageKind, message: Message, shape: RoundedCornerShape, fill: Color, text: Color) {
    val media = kind.media ?: return
    val author = (kind as? MessageKind.Share)?.let { ShareCaption.of(message.content).author }
    Row(Modifier.background(fill, shape).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.of(media.glyph), null, Modifier.size(20.dp), tint = text)
        Text(media.label + (author?.let { " · @$it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = text)
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
private fun ReactionPills(message: Message, people: People, isGroup: Boolean, modifier: Modifier, show: () -> Unit) {
    val spoken = "Reactions: " + message.reactions.joinToString(", ") { r ->
        if (isGroup) "${r.key} from ${r.senders.joinToString(" and ") { if (it == AppModel.ME) "you" else people.name(it) }}"
        else if (r.count > 1) "${r.key} ${r.count}" else r.key
    }
    Row(
        modifier.shadow(2.dp, CircleShape).border(2.dp, MaterialTheme.colorScheme.background, CircleShape).background(Bubbles.reaction, CircleShape)
            .clickable(onClick = show).heightIn(min = 24.dp).padding(horizontal = 6.dp, vertical = 2.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(if (isGroup) 6.dp else 2.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        for (r in message.reactions) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
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

/** The thread's own colours (DESIGN §8.1): the incoming bubble and the reaction pills. */
object Bubbles {
    val incoming: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF262629) else Color(0xFFE9E9EB)
    val reaction: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2C2C2E) else Color.White
}

/** A time label above a block: the time today, else the date and time, in the phone's format. */
private class ThreadTimes(private val today: DateTimeFormatter, private val other: DateTimeFormatter) {
    fun label(ts: Long): String {
        val date = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        val now = Instant.now().atZone(ZoneId.systemDefault())
        return date.format(if (date.toLocalDate() == now.toLocalDate()) today else other)
    }
}

@Composable
private fun rememberThreadTimes(): ThreadTimes {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val h24 = DateFormat.is24HourFormat(context)
    return remember(locale, h24) {
        fun best(skeleton: String) = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        ThreadTimes(best(if (h24) "Hm" else "hm"), best(if (h24) "dMMMHm" else "dMMMhm"))
    }
}
