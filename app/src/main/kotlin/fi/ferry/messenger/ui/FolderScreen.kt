package fi.ferry.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.Inbox.Folder
import fi.ferry.messenger.model.Room

/** I2–I4: one folder in a scope. In All, Requests has a section per account (DESIGN §4.3). Read-only for now. */
@Composable
fun FolderScreen(folder: Folder, scope: Inbox.Scope, openThread: (String) -> Unit, back: () -> Unit) {
    BackHandler(onBack = back)
    // Looking at Requests clears the card and the badge for what's listed; leaving covers what arrived meanwhile.
    if (folder == Folder.REQUESTS) {
        DisposableEffect(scope) {
            AppModel.markRequestsSeen(scope)
            onDispose { AppModel.markRequestsSeen(scope) }
        }
    }
    val rooms = Inbox.rooms(AppModel.rooms.values, scope, folder, AppModel.loginIDs)
    val multi = AppModel.accounts.size > 1
    val scopeName = (scope as? Inbox.Scope.Account)?.let { AppModel.identities[it.id]?.name }
    val times = rememberTimeFormats()
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding)) {
            Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp)) {
                IconButton(back, Modifier.align(Alignment.CenterStart)) { Icon(Icons.back, "Back") }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(folder.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(scopeName ?: "All accounts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AppModel.toast?.let { Notice(it, error = false) { AppModel.toast = null } }
            AppModel.actionError?.let { Notice(it, error = true) { AppModel.actionError = null } }
            if (AppModel.initialSyncDone && rooms.isEmpty()) {
                EmptyFolder(folder, scope)
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (folder == Folder.REQUESTS && rooms.isNotEmpty()) item("explainer") { Explainer() }
                if (folder == Folder.REQUESTS && scope == Inbox.Scope.All && multi) {
                    for (login in AppModel.orderedAccounts) {
                        val section = rooms.filter { it.bridge?.receiver == login.id }
                        val identity = AppModel.identities[login.id] ?: continue
                        if (section.isEmpty()) continue
                        item("header-${login.id}") {
                            Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AccountTag(identity)
                                Text("${section.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        rows(section, showsMark = false, times, openThread)
                    }
                } else {
                    rows(rooms, showsMark = scope == Inbox.Scope.All && multi, times, openThread)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rows(rooms: List<Room>, showsMark: Boolean, times: Inbox.TimeFormats, open: (String) -> Unit) {
    items(rooms, key = { it.id }) { room ->
        ArchiveActions(room, { open(room.id) }) { ChatRow(room, showsMark, times, it) }
        if (Inbox.isRequest(room)) RequestButtons(room)
        HorizontalDivider(Modifier.padding(start = 86.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * DESIGN I2 (iOS `RequestRow`): Delete and Accept under a request's row, in line with its text, the same calls as the
 * thread's request bar. Delete asks first, as there.
 */
@Composable
private fun RequestButtons(room: Room) {
    var confirming by remember(room.id) { mutableStateOf(false) }
    Row(Modifier.padding(start = 86.dp, end = 16.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("Delete", filled = false, color = Status.error) { confirming = true }
        SmallButton("Accept", filled = true) { AppModel.accept(room) }
    }
    if (confirming) DeleteRequestDialog(room, { confirming = false }) { AppModel.deleteRequest(room) }
}

/** A small capsule button (iOS's `.controlSize(.small)`): 32 dp, a 48 dp target by Compose's own touch slop. Filled in the primary colour, else outlined in `color`. */
@Composable
fun SmallButton(text: String, filled: Boolean, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    val modifier = Modifier.height(32.dp)
    val padding = PaddingValues(horizontal = 14.dp)
    val label: @Composable RowScope.() -> Unit = { Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
    if (filled) Button(onClick, modifier, contentPadding = padding, content = label)
    else OutlinedButton(onClick, modifier, contentPadding = padding, border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color), content = label)
}

/** DESIGN I2: what a request is, and the privacy it keeps. */
@Composable
private fun Explainer() {
    Text(
        "Requests come from people you don't follow. They won't see that you've read their message until you accept.",
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp)).padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun EmptyFolder(folder: Folder, scope: Inbox.Scope) {
    when (folder) {
        Folder.REQUESTS -> {
            val handle = (scope as? Inbox.Scope.Account)?.let { s -> AppModel.accounts.firstOrNull { it.id == s.id }?.profile?.username }
            Empty(Icons.requests, "No message requests",
                "When someone you don't follow messages ${handle?.let { "@$it" } ?: "your accounts"}, it shows up here.")
        }
        Folder.GENERAL -> Empty(Icons.general, "Nothing in General", "On Instagram, business and creator accounts can move chats here.")
        else -> Empty(Icons.archive, "No archived chats", "Archived chats come back when there's a new message.")
    }
}
