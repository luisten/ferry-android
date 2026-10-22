package fi.ferry.messenger.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
import fi.ferry.messenger.model.Message
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.RecentReactions
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
)

/**
 * A long press opens the message's menu: the reaction row, Reply, Copy, Edit, Unsend (or Delete for a failed send).
 * A double tap hearts it, like Instagram; a tap on a failed send retries it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageMenu(message: Message, actions: MessageActions, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val tap = remember { TapTarget() }
    var confirmUnsend by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val canSend = !actions.readOnly && message.eventID != null
    val mine = message.reactions.firstOrNull { it.myEventID != null }?.key
    val copyable = message.shareText ?: message.body.takeIf { message.kind.media == null && it.isNotEmpty() }
    val failed = message.status is Message.Status.Failed
    Box(
        Modifier.combinedClickable(
            onClick = { if (failed) actions.retry(message) else tap.action?.invoke() },
            onDoubleClick = if (canSend && message.kind.media == null && (mine == null || !Message.Reaction.same(mine, "❤️"))) ({ actions.react("❤️", message) }) else null,
            onLongClick = { if (message.kind != MessageKind.Unsent) open = true },
        ).semantics { if (message.kind != MessageKind.Unsent) onLongClick("React or reply") { open = true; true } },
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
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, destructive: Boolean = false, action: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
    Row(Modifier.fillMaxWidth().clickable(onClick = action).heightIn(min = 50.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(24.dp), tint = color)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
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
 * DESIGN §6.9: the account's avatar (the chat is locked to it), the field ("Message as Studio"), and once there's text a
 * send button in the account's colour. A counter from 900 characters; past 1000 (Instagram's limit) Send is off.
 */
@Composable
fun Composer(
    draft: TextFieldValue,
    change: (TextFieldValue) -> Unit,
    identity: AccountIdentity?,
    login: BridgeAPI.Login?,
    mode: ComposerMode?,
    cancel: () -> Unit,
    send: () -> Unit,
    focus: FocusRequester,
    attachment: AttachmentState? = null,
    /** The photo picker; null where there are no attachments (editing, a request, a reply that accepts one). */
    attach: (() -> Unit)? = null,
    removeAttachment: () -> Unit = {},
    /** A recorded voice note to send; null where there are none (as for attachments). */
    sendVoice: ((Attachment) -> Unit)? = null,
) {
    val account = identity?.name ?: login?.displayName ?: "this account"
    val count = draft.text.trim().length
    val ready = attachment is AttachmentState.Ready
    val fill = identity?.color?.fillColor ?: MaterialTheme.colorScheme.onBackground
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (mode) {
            is ComposerMode.Replying -> ModeBar(Icons.reply, "Replying to ${mode.name}", mode.snippet, cancel)
            ComposerMode.Editing -> ModeBar(Icons.edit, "Editing message", null, cancel)
            ComposerMode.AcceptsRequest -> ModeBar(Icons.check, "Replying accepts the request.", null, cancel)
            null -> {}
        }
        attachment?.let { AttachmentBar(it, removeAttachment) }
        val recording = (VoiceRecorder.recording || VoiceRecorder.review != null) && sendVoice != null
        // As iOS: "+" in its own circle left of the field.
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!recording) attach?.let {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClickLabel = "Photo or video", onClick = it),
                contentAlignment = Alignment.Center) { Icon(Icons.add, "Photo or video", tint = MaterialTheme.colorScheme.onBackground) }
        }
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
                VoiceRecorder.review?.let { ReviewPlayer(it) } ?: RecordingStrip(sendVoice!!)
            } else Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
                BasicTextField(
                    draft, change, Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Message, sending as $account" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground), maxLines = 6,
                    decorationBox = { field ->
                        if (draft.text.isEmpty()) Text("Message as $account", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        field()
                    },
                )
                if (count >= 900) {
                    val over = count > Timeline.MAX_TEXT_LENGTH
                    Text(if (over) "${count - Timeline.MAX_TEXT_LENGTH} over the limit" else "${Timeline.MAX_TEXT_LENGTH - count} left",
                        style = MaterialTheme.typography.labelSmall, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (count == 0 && attachment == null && sendVoice != null) {
                MicButton(sendVoice)
            } else if (count > 0 || ready) {
                val enabled = count <= Timeline.MAX_TEXT_LENGTH && attachment !is AttachmentState.Preparing
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
private fun MicButton(send: (Attachment) -> Unit) {
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
        if (sending) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.onBackground, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.send, "Send voice message", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.background)
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

/** A pick on its way into the composer: being prepared (`Outgoing`), or ready to send. */
sealed interface AttachmentState {
    data class Preparing(val what: String) : AttachmentState
    data class Ready(val attachment: Attachment) : AttachmentState
}

/** DESIGN §6.9's attachment bar: the pick, what it is, that a caption goes on its own (Instagram drops them), and ✕. */
@Composable
private fun AttachmentBar(state: AttachmentState, remove: () -> Unit) {
    val ready = (state as? AttachmentState.Ready)?.attachment
    val title = when {
        ready == null -> "Preparing ${(state as AttachmentState.Preparing).what}…"
        ready.kind == Attachment.Kind.VIDEO -> "Video" + (ready.duration?.let { " · ${clock(it)}" } ?: "")
        ready.kind == Attachment.Kind.GIF -> "GIF"
        else -> "Photo"
    }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(vertical = 8.dp).size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center) {
            ready?.preview?.let { Image(it, null, Modifier.size(44.dp), contentScale = ContentScale.Crop) }
                ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text("Your text is sent as a separate message.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(remove) { Icon(Icons.close, "Remove") }
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
    Text("Not sent. Tap to retry.", Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = retry).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
}
