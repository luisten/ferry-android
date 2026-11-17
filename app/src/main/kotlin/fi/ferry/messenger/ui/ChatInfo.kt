package fi.ferry.messenger.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.Room

/**
 * T3 chat info, minimal (DESIGN T3, as iOS): who the chat is with, which of your accounts it belongs to, their
 * Instagram profile when the handle is known, a group's members, and Archive. Not in v1: mute, search in chat, shared
 * media, delete, actions on members. Drawn over the thread, which stays as it was underneath.
 */
@Composable
fun ChatInfoScreen(room: Room, back: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    val identity = identityOf(room)
    val login = AppModel.accounts.firstOrNull { it.id == room.bridge?.receiver }
    val handle = AppModel.contactHandle(room)
    val folder = Inbox.folder(room)
    val isGroup = room.bridge?.isDM == false
    // The thread's start block says the same: "4 members", "@handle".
    val subtitle = Inbox.startBlock(isGroup, Inbox.memberCount(room), handle, null, false).subtitle
    var person by remember { mutableStateOf<Inbox.Member?>(null) }
    Scaffold(containerColor = Grouped.page) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp)) {
                IconButton(back, Modifier.align(Alignment.CenterStart)) { Icon(Icons.back, "Back") }
                Text("Chat Info", Modifier.align(Alignment.Center).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            CompositionLocalProvider(LocalMarkRing provides Grouped.card) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.padding(bottom = 6.dp)) { CompositionLocalProvider(LocalMarkRing provides Grouped.page) { ChatAvatar(room, 88.dp) } }
                        Text(room.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (identity != null) GroupedSection(footer = "Replies always come from this account.") {
                        // "Account", not "In [tag]" (iOS review 2026-10-15 #6), with the handle.
                        InfoRow("Account") { AccountTag(identity, handle = login?.profile?.username) }
                        if (folder == Inbox.Folder.GENERAL) {
                            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            InfoRow("Folder") { Text("General", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    if (isGroup) {
                        // Read-only (no member actions yet): a tap shows who someone is. Everyone came with the thread.
                        val members = Inbox.members(room, AppModel.ME, AppModel.profiles)
                        GroupedSection("${members.size} ${if (members.size == 1) "Member" else "Members"}", "To add or remove people, use Instagram.") {
                            members.forEachIndexed { i, member ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                MemberRow(member, identity, login?.profile?.username, login?.profile?.avatar, login?.displayName.orEmpty()) { person = member }
                            }
                        }
                    }
                    handle?.let { h ->
                        GroupedSection { ActionRow("Open in Instagram", Icons.openOutside) { openProfile(context, h) } }
                    }
                    if (folder != Inbox.Folder.REQUESTS) { // requests stay in Requests; archiving one would do nothing
                        val archived = folder == Inbox.Folder.ARCHIVED
                        GroupedSection(footer = if (archived) "It's in Archived. A new message brings it back by itself."
                            else "Archived chats come back when there's a new message. Instagram isn't changed.") {
                            ActionRow(if (archived) "Unarchive Chat" else "Archive Chat", null) { AppModel.setArchived(!archived, room) }
                        }
                    }
                }
            }
        }
    }
    person?.let { PersonCard(it.name, it.handle, it.avatarURL) { person = null } }
}

@Composable
private fun InfoRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        value()
    }
}

@Composable
private fun ActionRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = action).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        icon?.let { Icon(it, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * One member: picture, name and @handle when Instagram gave it. We're "You", with our account's picture and ring
 * (colour means account) and its mark, and not tappable.
 */
@Composable
private fun MemberRow(member: Inbox.Member, identity: fi.ferry.messenger.model.AccountIdentity?, ownHandle: String?, ownAvatar: String?,
                      ownName: String, show: () -> Unit) {
    val handle = if (member.isMe) ownHandle else member.handle
    val spoken = listOfNotNull(if (member.isMe) identity?.let { "You, ${it.name} account" } ?: "You" else member.name, handle?.let { "@$it" })
        .joinToString(", ")
    Row(
        Modifier.fillMaxWidth().then(if (member.isMe) Modifier else Modifier.clickable(onClickLabel = "Show who they are", onClick = show))
            .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp).clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 30 dp and its ring: as wide as the others' 36.
        if (member.isMe) AccountAvatar(identity, ownAvatar, ownName, 30.dp, mark = true) else Picture(member.avatarURL, member.name, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(member.name, style = MaterialTheme.typography.bodyLarge)
            handle?.let { Text("@$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * Who someone is, bigger than a 28 dp face (a tap on their picture in a group, or on a member in Chat Info): their
 * picture, name, @handle and Instagram profile. Only what the thread brought: nothing is asked of Instagram.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonCard(name: String, handle: String?, avatarURL: String?, dismiss: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(dismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // ponytail: the thread's picture is Instagram's small one (150 px), soft at this size; a bigger one costs a
            // profile request per tap.
            Box(Modifier.padding(bottom = 10.dp)) { Picture(avatarURL, name, 120.dp) }
            Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            handle?.let {
                Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton({ openProfile(context, it) }, Modifier.padding(top = 14.dp)) {
                    Icon(Icons.openOutside, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open in Instagram")
                }
            }
        }
    }
}

fun openProfile(context: Context, handle: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/$handle/")))
}
