package fi.ferry.messenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.ferry.messenger.model.AccountIdentity
import fi.ferry.messenger.model.BridgeAPI
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.Message
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.Timeline

/** What a message's menu can do; `readOnly` in a request (the engine refuses reactions, edits and unsends there). */
class MessageActions(
    val react: (String, Message) -> Unit,
    val reply: (Message) -> Unit,
    val edit: (Message) -> Unit,
    val unsend: (Message) -> Unit,
    val retry: (Message) -> Unit,
    val discard: (Message) -> Unit,
    val readOnly: Boolean,
)

/** Instagram's quick reactions (DESIGN §6.2). */
private val palette = listOf("❤️", "😂", "😮", "😢", "🔥", "👍")

/**
 * A long press opens the message's menu: the reaction row, Reply, Copy, Edit, Unsend (or Delete for a failed send).
 * A double tap hearts it, like Instagram; a tap on a failed send retries it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageMenu(message: Message, actions: MessageActions, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmUnsend by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val canSend = !actions.readOnly && message.eventID != null
    val mine = message.reactions.firstOrNull { it.myEventID != null }?.key
    val copyable = message.shareText ?: message.body.takeIf { message.kind.media == null && it.isNotEmpty() }
    val failed = message.status is Message.Status.Failed
    Box(
        Modifier.combinedClickable(
            onClick = { if (failed) actions.retry(message) },
            onDoubleClick = if (canSend && message.kind.media == null && (mine == null || !Message.Reaction.same(mine, "❤️"))) ({ actions.react("❤️", message) }) else null,
            onLongClick = { if (message.kind != MessageKind.Unsent) open = true },
        ).semantics { if (message.kind != MessageKind.Unsent) onLongClick("Message options") { open = true; true } },
    ) {
        content()
        DropdownMenu(open, { open = false }) {
            if (canSend) {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (key in palette) {
                        Text(
                            key,
                            Modifier.clip(CircleShape)
                                .background(if (mine != null && Message.Reaction.same(key, mine)) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                .combinedClickable(onClick = { open = false; actions.react(key, message) })
                                .padding(6.dp)
                                .semantics { contentDescription = if (mine != null && Message.Reaction.same(key, mine)) "Remove $key" else "React $key" },
                            fontSize = 24.sp,
                        )
                    }
                }
                HorizontalDivider()
                DropdownMenuItem({ Text("Reply") }, { open = false; actions.reply(message) }, leadingIcon = { Icon(Icons.reply, null) })
            }
            copyable?.let { text ->
                DropdownMenuItem({ Text("Copy") }, { open = false; copy(context, text) }, leadingIcon = { Icon(Icons.copy, null) })
            }
            if (!actions.readOnly && Timeline.canEdit(message)) {
                DropdownMenuItem({ Text("Edit") }, { open = false; actions.edit(message) }, leadingIcon = { Icon(Icons.edit, null) })
            }
            if (!actions.readOnly && message.isOwn && message.eventID != null) {
                DropdownMenuItem({ Text("Unsend", color = MaterialTheme.colorScheme.error) }, { open = false; confirmUnsend = true },
                    leadingIcon = { Icon(Icons.unsend, null, tint = MaterialTheme.colorScheme.error) })
            } else if (message.isOwn && failed) {
                DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { open = false; actions.discard(message) },
                    leadingIcon = { Icon(Icons.delete, null, tint = MaterialTheme.colorScheme.error) })
            }
        }
    }
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
) {
    val account = identity?.name ?: login?.displayName ?: "this account"
    val count = draft.text.trim().length
    val fill = identity?.color?.fillColor ?: MaterialTheme.colorScheme.onBackground
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (mode) {
            is ComposerMode.Replying -> ModeBar(Icons.reply, "Replying to ${mode.name}", mode.snippet, cancel)
            ComposerMode.Editing -> ModeBar(Icons.edit, "Editing message", null, cancel)
            ComposerMode.AcceptsRequest -> ModeBar(Icons.check, "Replying accepts the request.", null, cancel)
            null -> {}
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
                .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Box(Modifier.padding(bottom = 2.dp).semantics(mergeDescendants = true) { contentDescription = "Sending as $account" }) {
                AccountAvatar(identity, login?.profile?.avatar, account, 28.dp)
            }
            Column(Modifier.weight(1f).padding(vertical = 9.dp)) {
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
            if (count > 0) {
                val enabled = count <= Timeline.MAX_TEXT_LENGTH
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
