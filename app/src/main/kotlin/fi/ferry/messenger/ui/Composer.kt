package fi.ferry.messenger.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.Attachment
import fi.ferry.messenger.VoicePlayer
import fi.ferry.messenger.VoiceRecorder
import fi.ferry.messenger.model.AccountIdentity
import fi.ferry.messenger.model.BridgeAPI
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.MediaInfo
import fi.ferry.messenger.model.Message
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.RecentReactions
import fi.ferry.messenger.model.Room
import fi.ferry.messenger.model.Timeline
import fi.ferry.messenger.model.get
import fi.ferry.messenger.model.string
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a message's menu can do; `readOnly` in a request (the engine refuses reactions, edits and unsends there). */
class MessageActions(
    val react: (String, Message) -> Unit,
    val reply: (Message) -> Unit,
    val edit: (Message) -> Unit,
    val unsend: (Message) -> Unit,
    val retry: (Message) -> Unit,
    val discard: (Message) -> Unit,
    val readOnly: Boolean,
    /** "You", or a sender's first name: the sheet's line naming the message. */
    val firstName: (String) -> String = { it },
    /** Photos and videos sent together: all of them to Android's share sheet. */
    val shareAll: ((Message) -> Unit)? = null,
)

/**
 * A long press opens the message's menu: the reaction row, Reply, Copy, Edit, Unsend (or Delete for a failed send).
 * A double tap hearts it, like Instagram; a tap on a failed send retries it. `label`: TalkBack's words for the bubble
 * (decision A18), `extraActions` its other actions ("Open lowtide.band").
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageMenu(
    message: Message, actions: MessageActions, label: String? = null, extraActions: List<CustomAccessibilityAction> = emptyList(),
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val tap = remember { TapTarget() }
    var confirmUnsend by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val canSend = !actions.readOnly && message.eventID != null
    val mine = message.reactions.firstOrNull { it.myEventID != null }?.key
    val copyable = message.shareText ?: message.body.takeIf { message.kind.media == null && it.isNotEmpty() }
    val failed = message.status is Message.Status.Failed
    val click = { if (failed) actions.retry(message) else tap.action?.invoke() }
    val heart = if (canSend && message.kind.media == null && (mine == null || !Message.Reaction.same(mine, "❤️"))) ({ actions.react("❤️", message) }) else null
    SideEffect {
        tap.hold = { if (message.kind != MessageKind.Unsent) open = true }
        tap.click = { click() }
        tap.heart = heart
    }
    Box(
        Modifier.combinedClickable(
            onClick = { click() },
            onDoubleClick = heart,
            onLongClick = { if (message.kind != MessageKind.Unsent) open = true },
        ).semantics {
            label?.let { contentDescription = it }
            if (extraActions.isNotEmpty()) customActions = extraActions
            if (message.kind != MessageKind.Unsent) onLongClick("React or reply") { open = true; true }
        },
    ) {
        CompositionLocalProvider(LocalTap provides tap) { content() }
    }
    if (open) MessageSheet(message, actions, canSend, mine, copyable, failed, close = { open = false }, unsend = { confirmUnsend = true })
    if (confirmUnsend) {
        AlertDialog(
            onDismissRequest = { confirmUnsend = false },
            title = { Text("Unsend message?") },
            text = { Text("It will be removed for everyone in this chat.") },
            confirmButton = {
                TextButton({ confirmUnsend = false; actions.unsend(message) }) { Text("Unsend", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmUnsend = false }) { Text("Cancel") } },
        )
    }
}

/**
 * iOS 1.5's long-press sheet: one line naming the message, big buttons with the last reactions used and "+" for any
 * emoji, then Reply, Copy, Edit, Open in Instagram, Unsend or Delete. An action runs once the sheet is gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageSheet(
    message: Message, actions: MessageActions, canSend: Boolean, mine: String?, copyable: String?, failed: Boolean,
    close: () -> Unit, unsend: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    // Read once: the row never reshuffles while it's open.
    val row = remember { RecentReactions.shown(AppModel.recentReactions(), mine) }
    var picking by remember { mutableStateOf(false) }
    fun then(action: () -> Unit) {
        scope.launch { state.hide() }.invokeOnCompletion { close(); action() }
    }
    ModalBottomSheet(close, sheetState = state, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("${actions.firstName(message.sender)}: ${message.kind.media?.label ?: message.body}", maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (canSend) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    for (key in row) {
                        val ours = mine != null && Message.Reaction.same(key, mine)
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(if (ours) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable { then { actions.react(key, message) } }
                                .semantics { contentDescription = if (ours) "Remove $key" else "React $key"; selected = ours },
                            contentAlignment = Alignment.Center,
                        ) { Text(key, fontSize = 30.sp) }
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
                            .clickable { picking = true }.semantics { contentDescription = "All emoji" },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.add, null, tint = MaterialTheme.colorScheme.onBackground) }
                }
                if (picking) EmojiField { key -> then { actions.react(key, message) } }
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f))) {
                if (canSend) SheetRow(Icons.reply, "Reply") { then { actions.reply(message) } }
                copyable?.let { text -> SheetRow(Icons.copy, "Copy") { copy(context, text); then {} } }
                if (!actions.readOnly && Timeline.canEdit(message)) SheetRow(Icons.edit, "Edit") { then { actions.edit(message) } }
                val album = MediaInfo(message.content)
                if (album.album.size > 1 && actions.shareAll != null) {
                    SheetRow(Icons.share, "Share All", detail = album.albumLabel) { then { actions.shareAll.invoke(message) } }
                }
                message.content["external_url"].string?.takeIf { it.startsWith("https://") }?.let { url ->
                    SheetRow(Icons.openOutside, "Open in Instagram") {
                        then { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    }
                }
                if (!actions.readOnly && message.isOwn && message.eventID != null) {
                    SheetRow(Icons.unsend, "Unsend", destructive = true) { then(unsend) }
                } else if (message.isOwn && failed) {
                    SheetRow(Icons.delete, "Delete", destructive = true) { then { actions.discard(message) } }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String? = null, destructive: Boolean = false,
                     action: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
    Row(Modifier.fillMaxWidth().clickable(onClick = action).heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = color)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * "+": any emoji. Android can't ask the keyboard for its emoji page, so the field opens it with a hint; the first emoji
 * typed is the reaction, anything else is ignored. ponytail: no emoji grid of our own (androidx emoji2-emojipicker
 * would be a new library).
 */
@Composable
private fun EmojiField(pick: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    androidx.compose.material3.OutlinedTextField(text, { typed ->
        val emoji = graphemes(typed).firstOrNull(::isEmoji)
        if (emoji != null) pick(emoji) else text = ""
    }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
        placeholder = { Text("Choose an emoji from your keyboard") })
}

/** One emoji: its first code point is an emoji shown as one, or it's a sequence (❤️, 👍🏽, 👨‍👩‍👧). Not digits or letters. */
fun isEmoji(grapheme: String): Boolean {
    val first = grapheme.codePointAt(0)
    if (!android.icu.lang.UCharacter.hasBinaryProperty(first, android.icu.lang.UProperty.EMOJI)) return false
    return android.icu.lang.UCharacter.hasBinaryProperty(first, android.icu.lang.UProperty.EMOJI_PRESENTATION) ||
        grapheme.codePointCount(0, grapheme.length) > 1
}

private fun graphemes(text: String): List<String> {
    val it = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
    val out = mutableListOf<String>()
    var start = it.first()
    var end = it.next()
    while (end != java.text.BreakIterator.DONE) {
        out += text.substring(start, end)
        start = end
        end = it.next()
    }
    return out
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Message", text))
}

/** What the composer is doing besides a new message. */
sealed interface ComposerMode {
    data class Replying(val name: String, val snippet: String) : ComposerMode
    data object Editing : ComposerMode
    /** "Reply instead" in a request: sending accepts it (the engine accepts first). */
    data object AcceptsRequest : ComposerMode
}

/**
 * What "+" offers: the photo picker, and on a phone with a camera the camera app's photo and video (D4). `paste`: photos
 * and videos pasted into the field, or a keyboard's (D5).
 */
class AttachActions(val library: () -> Unit, val takePhoto: (() -> Unit)?, val recordVideo: (() -> Unit)?, val paste: (List<Uri>) -> Unit)

/**
 * DESIGN §6.9: the account's avatar (the chat is locked to it), the field ("Message as Studio"), and once there's text or
 * a photo a send button in the account's colour. A counter from 900 characters; past 1000 (Instagram's limit) Send is
 * off, and while a photo or video is still being prepared.
 */
@Composable
fun Composer(
    draft: TextFieldState,
    identity: AccountIdentity?,
    login: BridgeAPI.Login?,
    mode: ComposerMode?,
    cancel: () -> Unit,
    send: () -> Unit,
    focus: FocusRequester,
    /** Photos and videos waiting above the field, in the order they go. */
    items: List<ComposerItem> = emptyList(),
    /** "+"; null where there are no attachments (editing, a request, a reply that accepts one). */
    attach: AttachActions? = null,
    removeAttachment: (Long) -> Unit = {},
    /** A recorded voice note to send; null where there are none (as for attachments). */
    sendVoice: ((Attachment) -> Unit)? = null,
) {
    val account = identity?.name ?: login?.displayName ?: "this account"
    val count = draft.text.trim().length
    val context = LocalContext.current
    val fill = identity?.color?.fillColor ?: MaterialTheme.colorScheme.onBackground
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (mode) {
            is ComposerMode.Replying -> ModeBar(Icons.reply, "Replying to ${mode.name}", mode.snippet, cancel)
            ComposerMode.Editing -> ModeBar(Icons.edit, "Editing message", null, cancel)
            ComposerMode.AcceptsRequest -> ModeBar(Icons.check, "Replying accepts the request.", null, cancel)
            null -> {}
        }
        if (items.size == 1) AttachmentBar(items[0], typed = count > 0) { removeAttachment(items[0].id) }
        else if (items.size > 1) SetBar(items, typed = count > 0, removeAttachment)
        val recording = (VoiceRecorder.recording || VoiceRecorder.review != null) && sendVoice != null
        // As iOS: "+" in its own circle left of the field.
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!recording) attach?.let { PlusButton(it, enabled = items.size < MAX_ATTACHMENTS) }
        Row(
            Modifier.weight(1f).heightIn(min = 44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
                .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.padding(bottom = 2.dp).semantics(mergeDescendants = true) { contentDescription = "Sending as $account" }) {
                AccountAvatar(identity, login?.profile?.avatar, account, 28.dp)
                MarkOnAvatar(identity, 28.dp, 12.dp)
            }
            // While recording the field gives way to the strip; the mic stays where it is (it holds the press).
            if (recording) Box(Modifier.weight(1f)) {
                VoiceRecorder.review?.let { ReviewPlayer(it) } ?: RecordingStrip(sendVoice)
            } else Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                BasicTextField(
                    draft,
                    // Always on, so the keyboard is always told the field takes images: added only with "+", it stayed off
                    // after an Edit (the keyboard reads it when its session starts, R2-F4). Where "+" isn't, a paste says why.
                    Modifier.fillMaxWidth().focusRequester(focus).pastes(context, attach?.paste ?: { _ ->
                        AppModel.actionError = if (mode == ComposerMode.Editing) "An edit can only change the text." else "Only text can be sent here."
                    })
                        .semantics { contentDescription = "Message, sending as $account" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                    decorator = { field ->
                        Box {
                            if (draft.text.isEmpty()) Text("Message as $account", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            field()
                        }
                    },
                )
                if (count >= 900) {
                    val over = count > Timeline.MAX_TEXT_LENGTH
                    Text(if (over) "${count - Timeline.MAX_TEXT_LENGTH} over the limit" else "${Timeline.MAX_TEXT_LENGTH - count} left",
                        style = MaterialTheme.typography.labelSmall, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (count == 0 && items.isEmpty() && sendVoice != null) {
                MicButton(fill, sendVoice)
            } else if (count > 0 || items.isNotEmpty()) {
                val enabled = count <= Timeline.MAX_TEXT_LENGTH && (mode == ComposerMode.Editing || items.all { it.ready != null })
                IconButton(send, Modifier.size(40.dp), enabled = enabled) {
                    Box(Modifier.size(32.dp).background(fill.copy(alpha = if (enabled) 1f else 0.4f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (mode == ComposerMode.Editing) Icons.check else Icons.send, if (mode == ComposerMode.Editing) "Save edit" else "Send",
                            Modifier.size(20.dp), tint = Color.White)
                    }
                }
            }
        }
        }
    }
}

/**
 * DESIGN §6.9's mic: a tap records hands-free (then Send or the bin); held, it records until let go, and sends if the
 * note is a second long. The first press asks for the microphone.
 */
@Composable
private fun MicButton(fill: Color, send: (Attachment) -> Unit) {
    val context = LocalContext.current
    val sending = VoiceRecorder.recording || VoiceRecorder.review != null
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) AppModel.actionError = "Voice messages need the microphone. Allow it in Settings, under Ferry Messenger's permissions."
    }
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .semantics { contentDescription = if (sending) "Send voice message" else "Record a voice message" }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (VoiceRecorder.recording || VoiceRecorder.review != null) { // hands-free or listening back: Send
                        VoicePlayer.stop()
                        finish(send)
                        return@detectTapGestures
                    }
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                        return@detectTapGestures
                    }
                    if (!VoiceRecorder.start(context)) {
                        AppModel.actionError = "The microphone is busy. Try again."
                        return@detectTapGestures
                    }
                    VoicePlayer.stop()
                    val pressed = SystemClock.elapsedRealtime()
                    tryAwaitRelease()
                    if (VoiceRecorder.sendsOnRelease(SystemClock.elapsedRealtime() - pressed, VoiceRecorder.elapsed)) {
                        VoiceRecorder.finish()?.let(send)
                    } else if (VoiceRecorder.recording) {
                        VoiceRecorder.handsFree = true // a tap, or let go too soon: Stop and listen back are offered
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (VoiceRecorder.recording && !VoiceRecorder.handsFree) {
            // Held: the mic on the account's colour (iOS's `mic.fill` on a disc).
            Box(Modifier.size(40.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.mic, null, Modifier.size(24.dp), tint = Color.White)
            }
        } else if (sending) {
            Box(Modifier.size(32.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.send, "Send voice message", Modifier.size(20.dp), tint = Color.White)
            }
        } else {
            Icon(Icons.mic, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

/** The note, or "Too short to send" when it's under a second. */
private fun finish(send: (Attachment) -> Unit) {
    val note = VoiceRecorder.finish()
    if (note != null) send(note) else AppModel.actionError = "Too short to send."
}

/** While recording, in the field's place: the bin, a red dot and the time, a level meter. Five minutes sends it. */
@Composable
private fun RecordingStrip(send: (Attachment) -> Unit) {
    var elapsed by remember { mutableLongStateOf(0L) }
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        while (VoiceRecorder.recording) {
            elapsed = VoiceRecorder.elapsed
            levels.add(VoiceRecorder.level())
            if (levels.size > 16) levels.removeAt(0)
            if (elapsed >= VoiceRecorder.MAX_MS) {
                VoiceRecorder.finish()?.let(send)
                break
            }
            delay(50)
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton({ VoiceRecorder.cancel() }) { Icon(Icons.delete, "Delete recording", tint = MaterialTheme.colorScheme.error) }
        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        Text(clock(elapsed), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.weight(1f).height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            levels.forEach { level ->
                Box(Modifier.width(3.dp).height((4 + 20 * level).dp).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
        }
        // iOS 1.5: hands-free, Stop ends it to listen back before sending. A hold has none.
        if (VoiceRecorder.handsFree) IconButton({
            if (!VoiceRecorder.stop()) AppModel.actionError = "Couldn't keep the recording."
        }) { Icon(Icons.stop, "Stop and listen back", tint = MaterialTheme.colorScheme.onBackground) }
    }
}

/** Listening back (iOS 1.5's ReviewPlayer): the bin, play or pause, the bars (drag to seek), the time. */
@Composable
private fun ReviewPlayer(note: Attachment) {
    val context = LocalContext.current
    val id = "composer.review"
    val duration = note.duration ?: 0L
    val loaded = VoicePlayer.current == id
    val playing = loaded && VoicePlayer.playing
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(loaded, playing) {
        position = if (loaded) VoicePlayer.position else 0
        while (playing) {
            position = VoicePlayer.position
            delay(100)
        }
    }
    val heights = remember(note.file.name) { voiceBars(note.file.name, 24) }
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconButton({ VoicePlayer.stop(); VoiceRecorder.cancel() }) { Icon(Icons.delete, "Delete recording", tint = MaterialTheme.colorScheme.error) }
        IconButton({ VoicePlayer.toggle(context, id, note.file) }, Modifier.semantics { stateDescription = clock(duration) }) {
            Icon(if (playing) Icons.pause else Icons.play, if (playing) "Pause recording" else "Play recording", tint = MaterialTheme.colorScheme.onBackground)
        }
        val ink = MaterialTheme.colorScheme.onBackground
        Canvas(Modifier.weight(1f).height(24.dp).pointerInput(duration, loaded) {
            if (!loaded || duration <= 0) return@pointerInput
            detectHorizontalDragGestures { change, _ -> VoicePlayer.seek(id, (change.position.x / size.width * duration).toLong().coerceIn(0, duration)) }
        }) {
            val bar = 3.dp.toPx()
            val gap = (size.width - bar * heights.size) / (heights.size - 1)
            val done = if (duration > 0) position.toFloat() / duration else 0f
            heights.forEachIndexed { i, fraction ->
                val h = size.height * fraction
                drawRoundRect(if (i.toFloat() / heights.size < done) ink else ink.copy(alpha = 0.35f),
                    Offset(i * (bar + gap), (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2))
            }
        }
        Text(clock(if (playing) position else duration), style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Photos and videos pasted (the field's Paste) or inserted by a keyboard (its clipboard, GIFs, stickers) go to `paste`
 * (D5); text and anything else goes on into the field. Another app's URIs: content ones only, never a file path, and a
 * photo or video by their provider's own type.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.pastes(context: Context, paste: (List<Uri>) -> Unit) = contentReceiver { content ->
    if (!content.hasMediaType(MediaType.Image) && !content.hasMediaType(VIDEO)) return@contentReceiver content
    val uris = mutableListOf<Uri>()
    val rest = content.consume { item -> item.uri?.takeIf { isPhotoOrVideo(context, it) }?.also { uris += it } != null }
    if (uris.isNotEmpty()) paste(uris)
    rest
}

private val VIDEO = MediaType("video/*")

private fun isPhotoOrVideo(context: Context, uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: return false
    return type.startsWith("image/") || type.startsWith("video/")
}

/** iOS's `maxAttachments`: the photos and videos one Send takes. */
const val MAX_ATTACHMENTS = 10

/** A photo or video waiting above the field (iOS's `ComposerItem`): being prepared (`Outgoing`) until it's `ready`. */
data class ComposerItem(val id: Long, val video: Boolean, val ready: Attachment? = null)

/**
 * "+" in its own circle (as iOS), off at 10 waiting. With a camera, a menu in iOS's order (A21): Take Photo, Record
 * Video (Android has no one intent for both), Choose from Library; without one, the photo picker at once.
 */
@Composable
private fun PlusButton(attach: AttachActions, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val menu = attach.takePhoto != null || attach.recordVideo != null
    val ink = MaterialTheme.colorScheme.onBackground
    Box {
        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClickLabel = "Add photos or videos") { if (menu) open = true else attach.library() },
            contentAlignment = Alignment.Center) {
            Icon(Icons.add, "Add photos or videos", tint = if (enabled) ink else ink.copy(alpha = 0.38f))
        }
        DropdownMenu(open, { open = false }) {
            attach.takePhoto?.let { take ->
                DropdownMenuItem({ Text("Take Photo") }, { open = false; take() }, leadingIcon = { Icon(Icons.camera, null) })
            }
            attach.recordVideo?.let { record ->
                DropdownMenuItem({ Text("Record Video") }, { open = false; record() }, leadingIcon = { Icon(Icons.video, null) })
            }
            DropdownMenuItem({ Text("Choose from Library") }, { open = false; attach.library() }, leadingIcon = { Icon(Icons.photo, null) })
        }
    }
}

/** DESIGN §6.9's attachment bar: the pick, what it is, that typed text goes on its own (Instagram drops captions), and ✕. */
@Composable
private fun AttachmentBar(item: ComposerItem, typed: Boolean, remove: () -> Unit) {
    val ready = item.ready
    val title = when {
        ready == null -> if (item.video) "Preparing video…" else "Preparing photo…"
        ready.kind == Attachment.Kind.VIDEO -> "Video" + (ready.duration?.let { " · ${clock(it)}" } ?: "")
        ready.kind == Attachment.Kind.GIF -> "GIF"
        else -> "Photo"
    }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Thumbnail(item, 44.dp, Modifier.padding(vertical = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (ready != null && typed) {
                Text("Your text is sent as a separate message.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(remove) { Icon(Icons.close, if (item.video) "Remove video" else "Remove photo") }
    }
}

/** iOS 1.7's set: two or more waiting, each with its ✕. They go one after another, in this order, then the text. */
@Composable
private fun SetBar(items: List<ComposerItem>, typed: Boolean, remove: (Long) -> Unit) {
    val videos = items.count { it.video }
    val photos = items.size - videos
    val title = listOfNotNull(
        "$photos photo${if (photos == 1) "" else "s"}".takeIf { photos > 0 },
        "$videos video${if (videos == 1) "" else "s"}".takeIf { videos > 0 },
    ).joinToString(", ")
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(if (typed && items.all { it.ready != null }) "Each is sent on its own, then your text." else "Each is sent on its own, in this order.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEachIndexed { i, item ->
                key(item.id) {
                    Box {
                        Thumbnail(item, 56.dp)
                        Box(Modifier.align(Alignment.TopEnd).size(28.dp).clickable(role = Role.Button) { remove(item.id) }
                            .semantics { contentDescription = "Remove ${if (item.video) "video" else "photo"} ${i + 1}" },
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(20.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                                Icon(Icons.close, null, Modifier.size(14.dp), tint = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The pick's picture (a video's with ▶), or a spinner while it's prepared. */
@Composable
private fun Thumbnail(item: ComposerItem, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        val preview = item.ready?.preview
        if (preview != null) {
            Image(preview, null, Modifier.size(size), contentScale = ContentScale.Crop)
            if (item.video) Icon(Icons.play, null, Modifier.size(16.dp), tint = Color.White)
        } else if (item.ready == null) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * DESIGN T2: in place of the composer in a request. Opening one sends nothing (no "Seen" or typing reaches the
 * requester); only these buttons, or a reply, do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RequestBar(requester: String, identity: AccountIdentity?, accept: () -> Unit, delete: () -> Unit, replyInstead: () -> Unit) {
    val title = Inbox.requestTitle(requester, identity?.name)
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The account's tag ends the title's line, and wraps under it when both don't fit.
        FlowRow(Modifier.clearAndSetSemantics { contentDescription = title.spoken; heading() },
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title.shown, Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (identity != null) AccountTag(identity, Modifier.align(Alignment.CenterVertically), compact = true)
        }
        Text("They won't see that you've read this until you accept.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(delete, Modifier.weight(1f).heightIn(min = 44.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete", fontWeight = FontWeight.SemiBold) }
            Button(accept, Modifier.weight(1f).heightIn(min = 44.dp), colors = ButtonDefaults.buttonColors(
                containerColor = identity?.color?.fillColor ?: MaterialTheme.colorScheme.primary, contentColor = Color.White,
            )) { Text("Accept", fontWeight = FontWeight.SemiBold) }
        }
        TextButton(replyInstead, Modifier.align(Alignment.CenterHorizontally)) {
            Text("Reply instead", color = MaterialTheme.colorScheme.onBackground)
            Text(" · replying accepts", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "Delete request from X?", in the thread's request bar and in the Requests list: the same words, then `confirm`. */
@Composable
fun DeleteRequestDialog(room: Room, dismiss: () -> Unit, confirm: () -> Unit) {
    val login = AppModel.accounts.firstOrNull { it.id == room.bridge?.receiver }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Delete request from ${room.name}?") },
        // ponytail: iOS's copy; whether Instagram shows the requester a "Seen" is untested, so it keeps the warning.
        text = { Text("It's deleted from ${login?.profile?.username?.let { "@$it's" } ?: "your"} Instagram inbox too. They may see it as read.") },
        confirmButton = { TextButton({ dismiss(); confirm() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModeBar(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?, cancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
        IconButton(cancel) { Icon(Icons.close, "Cancel") }
    }
}

/** "Accepted. This chat is now in Studio." and failures, under the thread's header for a few seconds. */
@Composable
fun Notice(text: String, error: Boolean, dismiss: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(text) {
        kotlinx.coroutines.delay(4_000)
        dismiss()
    }
    Text(
        text,
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
            .background(if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(12.dp))
            .clickable(onClick = dismiss).padding(12.dp),
        color = if (error) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.inverseOnSurface,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** A failed send says so under the bubble; a tap on it (or the bubble) sends it again. */
@Composable
fun NotSent(retry: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = retry).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.failed, null, Modifier.size(14.dp), tint = Status.error)
        Text("Not sent. Tap to retry.", style = MaterialTheme.typography.labelMedium, color = Status.error)
    }
}
