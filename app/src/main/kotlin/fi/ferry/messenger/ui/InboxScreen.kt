package fi.ferry.messenger.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.Updates
import fi.ferry.messenger.model.BridgeAPI
import fi.ferry.messenger.model.Inbox
import fi.ferry.messenger.model.Inbox.Folder
import fi.ferry.messenger.model.Room
import java.time.ZoneId
import kotlin.math.abs
import kotlinx.coroutines.launch

/** I1, the inbox (DESIGN §4, I1): account stack, title, Folders menu, scope bar, the Requests card, chat rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    openThread: (String) -> Unit,
    openFolder: (Folder, Inbox.Scope) -> Unit,
    openAccounts: () -> Unit,
    newMessage: () -> Unit,
    relogin: (String) -> Unit,
) {
    val scope = AppModel.scope
    val rooms = AppModel.rooms.values
    val logins = AppModel.loginIDs
    val identity = (scope as? Inbox.Scope.Account)?.let { AppModel.identities[it.id] }
    val login = (scope as? Inbox.Scope.Account)?.let { s -> AppModel.accounts.firstOrNull { it.id == s.id } }
    val multiAccount = AppModel.accounts.size > 1
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    val searching = !query.isNullOrBlank()
    val list = if (searching) Inbox.search(rooms, query!!, scope, logins) else Inbox.rooms(rooms, scope, Folder.CHATS, logins)
    val newRequests = if (searching || !AppModel.initialSyncDone) emptyList() else Inbox.newRequests(rooms, scope, logins, AppModel.requestsSeen)
    // No first load yet and Instagram isn't answering: say so instead of showing placeholders for good.
    val unreachable = !AppModel.initialSyncDone && AppModel.syncFailures >= 2
    BackHandler(query != null) { query = null }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // DESIGN I1, as iOS: search on the left of the bottom bar, compose right of it.
            if (query == null) Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.weight(1f).height(52.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                        .clickable(onClickLabel = "Search") { query = "" }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.search, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (identity != null) "Search ${identity.name}" else "Search", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge)
                }
                if (AppModel.accounts.isNotEmpty()) Box(
                    Modifier.size(52.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                        .clickable(onClickLabel = "New message", onClick = newMessage),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.compose, "New message", Modifier.size(24.dp), tint = identity?.color?.ink ?: MaterialTheme.colorScheme.onBackground) }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // Account scope wears the account's colour: a faint wash fading down from the top (DESIGN §3.3).
            if (identity != null) {
                Box(Modifier.fillMaxWidth().height(220.dp).background(Brush.verticalGradient(listOf(identity.color.wash, Color.Transparent))))
            }
            Column(Modifier.padding(padding)) {
                if (query == null) {
                    TopBar(
                        title = identity?.name ?: "Chats",
                        subtitle = login?.let { Inbox.scopeSubtitle(it.profile?.username, it.isProfessional, AppModel.accounts.size) },
                        openAccounts = openAccounts,
                        menu = Inbox.foldersMenu(rooms, scope, AppModel.accounts, logins, AppModel.requestsSeen),
                        openFolder = { openFolder(it, scope) },
                    )
                } else {
                    SearchBar(query!!, if (identity != null) "Search ${identity.name}" else "Search", { query = it }, { query = null })
                }
                if (multiAccount) ScopeBar(scope)
                // Offline waits for the first sync's own "can't reach" (none yet): a banner over an empty list says less.
                Inbox.banner(AppModel.syncFailures, AppModel.accounts)?.takeIf { it != AppModel.hiddenBanner && (it != Inbox.Banner.Offline || AppModel.initialSyncDone) }
                    ?.let { ConnectionBanner(it, openAccounts, relogin) }
                Updates.banner?.let { UpdateBanner(it) }
                AppModel.toast?.let { Notice(it, error = false) { AppModel.toast = null } }
                AppModel.actionError?.let { Notice(it, error = true) { AppModel.actionError = null } }
                val times = rememberTimeFormats()
                // As iOS: pulling down reconnects an account that failed (nothing for connected ones).
                PullToRefreshBox(false, AppModel::restartSync, Modifier.fillMaxSize()) { LazyColumn(Modifier.fillMaxSize()) {
                    if (newRequests.isNotEmpty()) {
                        item(key = "requests") { RequestsCard(newRequests, scope == Inbox.Scope.All && multiAccount) { openFolder(Folder.REQUESTS, scope) } }
                    }
                    if (AppModel.initialSyncDone) {
                        items(list, key = { it.id }) { room ->
                            ArchiveActions(room, { openThread(room.id) }) { ChatRow(room, showsMark = scope == Inbox.Scope.All && multiAccount, times, it) }
                            HorizontalDivider(Modifier.padding(start = 86.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    } else if (!unreachable) {
                        items(8) { PlaceholderRow() }
                    }
                } }
            }
            if (unreachable) Empty(Icons.offline, "Can't reach Instagram", "Retrying. Your chats appear when it's back.") {
                FilledTonalButton(AppModel::restartSync) { Text("Try Again") }
            } else if (AppModel.initialSyncDone && list.isEmpty() && newRequests.isEmpty()) {
                when {
                    searching -> Empty(Icons.search, "No results for “${query!!.trim()}”", "Check the spelling or try a new search.")
                    else -> EmptyInbox(scope, identity?.name)
                }
            }
        }
    }
}

/**
 * DESIGN I1/I4: a row swiped left, or its long press, archives it, or unarchives it in Archived (for TalkBack, an
 * action). Requests have neither: they stay in Requests either way. iOS 1.7: swiped right, or from the same menu (before
 * Archive), Mark as Unread on a read chat and Mark as Read on one marked here, this phone only; neither on a chat
 * Instagram has unread (opening it reads it, and "Read" from here would have to send "Seen"). A swipe right starts
 * inside the row: from the screen's left edge it's Android's back (decision A28).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArchiveActions(room: Room, open: () -> Unit, row: @Composable (Modifier) -> Unit) {
    val folder = Inbox.folder(room)
    if (folder == Folder.REQUESTS) return row(Modifier.clickable(onClick = open))
    var menu by remember { mutableStateOf(false) }
    val archived = folder == Folder.ARCHIVED
    val label = if (archived) "Unarchive" else "Archive"
    val act = { menu = false; AppModel.setArchived(!archived, room) }
    val marked = room.isMarkedUnread
    val unreadLabel = if (!room.isUnread || marked) (if (marked) "Mark as Read" else "Mark as Unread") else null
    val unreadIcon = if (marked) Icons.markRead else Icons.markUnread
    val mark = { menu = false; AppModel.setMarkedUnread(!marked, room) }
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    // One `onDismiss` for the row's life: Material calls it again, while the row is still swiped, whenever it's handed a
    // new one, and a row that stays (marked unread) is recomposed by the change: it flipped back and forth for good.
    val dismissed by rememberUpdatedState { value: SwipeToDismissBoxValue -> if (value == SwipeToDismissBoxValue.StartToEnd) mark() else act() }
    val onDismiss = remember(swipe) { { value: SwipeToDismissBoxValue -> dismissed(value); scope.launch { swipe.reset() }; Unit } }
    // As iOS's full swipe: past the threshold it's done, and the row leaves the list (it snaps back if it stays).
    SwipeToDismissBox(
        swipe,
        {
            if (swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                if (unreadLabel != null) SwipeAction(swipe, unreadLabel, unreadIcon, start = true, fill = swipeUnread, ink = Color.White)
            } else SwipeAction(swipe, label, if (archived) Icons.unarchive else Icons.archive)
        },
        enableDismissFromStartToEnd = unreadLabel != null,
        onDismiss = onDismiss,
    ) {
        Box {
            row(Modifier.combinedClickable(onClick = open, onLongClick = { menu = true })
                .semantics {
                    customActions = listOfNotNull(
                        unreadLabel?.let { CustomAccessibilityAction(it) { mark(); true } },
                        CustomAccessibilityAction(label) { act(); true },
                    )
                })
            DropdownMenu(menu, { menu = false }, offset = DpOffset(86.dp, 0.dp)) {
                unreadLabel?.let { DropdownMenuItem({ Text(it) }, mark, leadingIcon = { Icon(unreadIcon, null) }) }
                DropdownMenuItem({ Text(label) }, act, leadingIcon = { Icon(if (archived) Icons.unarchive else Icons.archive, null) })
            }
        }
    }
}

/**
 * What a swipe uncovers: at the row's right for a left swipe, at its left (`start`) for a right one. Grey, never an
 * account's colour. Only as wide as the swipe, so the row's own background (the scope's wash) stays.
 */
@Composable
private fun RowScope.SwipeAction(
    swipe: SwipeToDismissBoxState, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, start: Boolean = false,
    fill: Color = swipeArchive, ink: Color = Color.White,
) {
    val width = with(LocalDensity.current) { abs(runCatching { swipe.requireOffset() }.getOrDefault(0f)).toDp() }
    if (!start) Spacer(Modifier.weight(1f))
    Box(Modifier.fillMaxHeight().width(width).background(fill), contentAlignment = Alignment.Center) {
        if (width > 64.dp) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = ink)
            Text(label, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
        }
    }
    if (start) Spacer(Modifier.weight(1f))
}

/** iOS's `swipeArchive`: neutral and dark, under white (Archive and Unarchive). */
private val swipeArchive: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF3A3A3C) else Color(0xFF1C1C1E)

/** iOS's `swipeUnread` (decision A20): a neutral grey, set apart from Archive's, under white. */
private val swipeUnread: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF636366) else Color(0xFF48484A)

/** Everything archived or in General isn't "no chats yet": say where they went. */
@Composable
private fun EmptyInbox(scope: Inbox.Scope, name: String?) {
    val rooms = AppModel.rooms.values
    val archived = Inbox.rooms(rooms, scope, Folder.ARCHIVED, AppModel.loginIDs).isNotEmpty()
    val general = Inbox.rooms(rooms, scope, Folder.GENERAL, AppModel.loginIDs).isNotEmpty()
    Empty(
        Icons.chats,
        if (archived || general) "All caught up" else name?.let { "No chats in $it yet" } ?: "No chats yet",
        when {
            general && archived -> "Your other chats are in General and Archived. Open Folders, top right."
            general -> "Your other chats are in General. Open Folders, top right."
            archived -> "Your other chats are in Archived. Open Folders, top right. A new message brings one back."
            name == null -> "New messages from all your accounts land here."
            else -> "New messages to this account land here."
        },
    )
}

@Composable
fun Empty(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        action?.let { Spacer(Modifier.height(16.dp)); it() }
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String?,
    openAccounts: () -> Unit,
    menu: Inbox.FoldersMenu,
    openFolder: (Folder) -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { AccountStackButton(openAccounts) }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Row(Modifier.align(Alignment.CenterEnd)) { FoldersMenu(menu, openFolder) }
    }
}

@Composable
private fun SearchBar(query: String, prompt: String, change: (String) -> Unit, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            query, change, Modifier.weight(1f),
            placeholder = { Text(prompt) }, singleLine = true,
            leadingIcon = { Icon(Icons.search, null) },
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        IconButton(close) { Icon(Icons.close, "Close search") }
    }
}

/** Your accounts side by side, never over each other (principle 9); a badge on the one that needs you. Opens Accounts. */
@Composable
private fun AccountStackButton(open: () -> Unit) {
    val logins = AppModel.orderedAccounts
    val stack = Inbox.AccountStack(logins.map { Inbox.AccountStack.Account(it.id, AppModel.identities[it.id]?.name ?: it.displayName, it.health) })
    Row(
        Modifier.heightIn(min = 48.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
            .clickable(onClickLabel = "Open Accounts", onClick = open)
            .padding(horizontal = 7.dp, vertical = 7.dp)
            .clearAndSetSemantics { contentDescription = stack.accessibilityLabel; role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stack.shown.isEmpty()) Icon(Icons.person, null, Modifier.size(30.dp))
        for (account in stack.shown) {
            val login = logins.firstOrNull { it.id == account.id }
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                AccountAvatar(AppModel.identities[account.id], login?.profile?.avatar, account.name, 24.dp)
                StatusBadge(account.status, Modifier.align(Alignment.BottomEnd))
            }
        }
        if (stack.hidden.isNotEmpty()) {
            Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
                Text("+${stack.hidden.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                StatusBadge(stack.hiddenStatus, Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}

/** A warning when the account needs you, a spinner while it connects; a cut-out in the background colour. */
@Composable
private fun StatusBadge(status: Inbox.AccountStack.Status?, modifier: Modifier) {
    status ?: return
    Box(modifier.size(14.dp).background(MaterialTheme.colorScheme.background, CircleShape), contentAlignment = Alignment.Center) {
        when (status) {
            Inbox.AccountStack.Status.NEEDS_YOU -> Icon(Icons.warning, null, Modifier.size(10.dp), tint = Status.warn)
            Inbox.AccountStack.Status.CONNECTING -> CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** General (where a professional account is in scope), Requests and Archived; subtitles and the badge only when non-zero. */
@Composable
private fun FoldersMenu(menu: Inbox.FoldersMenu, open: (Folder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        // As iOS: a white round button with a soft shadow, the count over the glyph's top-right corner.
        Box(Modifier.size(52.dp)) {
            Box(
                Modifier.size(48.dp).align(Alignment.Center).shadow(6.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                    .clickable { expanded = true }.semantics { contentDescription = menu.accessibilityLabel; role = Role.Button },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.folders, null, Modifier.size(24.dp)) }
            if (menu.badge > 0) {
                // Label colour, not red: colour means account.
                Text(
                    "${menu.badge}", Modifier.align(Alignment.TopEnd).offset((-1).dp, 2.dp).clearAndSetSemantics {}
                        .background(MaterialTheme.colorScheme.background, CircleShape).padding(2.dp)
                        .background(MaterialTheme.colorScheme.onBackground, CircleShape).padding(horizontal = 5.dp),
                    color = MaterialTheme.colorScheme.background, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            for (folder in menu.items) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(folder.title)
                            menu.subtitle(folder)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    onClick = { expanded = false; open(folder) },
                    leadingIcon = { Icon(folder.icon, null) },
                )
            }
        }
    }
}

val Folder.title: String
    get() = when (this) {
        Folder.CHATS -> "Chats"
        Folder.GENERAL -> "General"
        Folder.REQUESTS -> "Requests"
        Folder.ARCHIVED -> "Archived"
    }

val Folder.icon
    get() = when (this) {
        Folder.CHATS -> Icons.chats
        Folder.GENERAL -> Icons.general
        Folder.REQUESTS -> Icons.requests
        Folder.ARCHIVED -> Icons.archive
    }

/** The one control (DESIGN §4.1): All, then each account; the selected chip fills with its colour. */
@Composable
private fun ScopeBar(selected: Inbox.Scope) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val rooms = AppModel.rooms.values
        fun unread(scope: Inbox.Scope) = Inbox.unreadChats(rooms, scope, logins = AppModel.loginIDs)
        ScopeChip(Inbox.Scope.All, "All", null, unread(Inbox.Scope.All), null, selected == Inbox.Scope.All)
        for (login in AppModel.orderedAccounts) {
            val scope = Inbox.Scope.Account(login.id)
            ScopeChip(scope, AppModel.identities[login.id]?.name ?: login.displayName, AppModel.identities[login.id], unread(scope), login.health, selected == scope)
        }
    }
}

@Composable
private fun ScopeChip(
    scope: Inbox.Scope,
    label: String,
    identity: fi.ferry.messenger.model.AccountIdentity?,
    count: Int,
    health: BridgeAPI.Login.Health?,
    selected: Boolean,
) {
    val fill = if (selected) identity?.color?.fillColor ?: MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceVariant
    val text = if (selected) (if (identity == null) MaterialTheme.colorScheme.background else Color.White) else MaterialTheme.colorScheme.onSurface
    // "Studio, 3 unread" (DESIGN §3.7), or what the chip's glyph says instead of the count.
    val spoken = when {
        health?.needsUser == true -> "$label, needs attention"
        health?.isBusy == true -> "$label, ${health.label}"
        count > 0 -> "$label, $count unread"
        else -> label
    }
    Row(
        Modifier.heightIn(min = 44.dp).clip(CircleShape).clickable { AppModel.selectScope(scope) }
            .clearAndSetSemantics { contentDescription = spoken; role = Role.Tab; this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.heightIn(min = 34.dp).background(fill, CircleShape).padding(start = if (identity == null) 14.dp else 8.dp, end = if (identity == null) 14.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (identity != null) AccountMark(identity, 18.dp, inverted = selected)
            Text(label, color = text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            when {
                health?.needsUser == true -> Icon(Icons.warning, null, Modifier.size(14.dp), tint = Status.warn)
                health?.isBusy == true -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = text)
                count > 0 -> Text("$count", color = text.copy(alpha = if (selected) 0.85f else 0.6f), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** While new requests wait: who's waiting (with each account's mark in All), never what they wrote. Opens Requests. */
@Composable
private fun RequestsCard(requests: List<Room>, showsMarks: Boolean, open: () -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp).fillMaxWidth().heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = open)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = Inbox.requestsCardLabel(requests.map { it.name }); role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.background, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.requests, null, Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(Inbox.requestsCardTitle(requests.size), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            // Every name if they fit, else the first two or one and "+N" (rather than cutting each name short), as iOS.
            val counts = listOf(requests.size, 2, 1).filter { it <= requests.size }.distinct()
            FirstThatFits(counts.size) { i -> Names(requests, counts[i], showsMarks, last = i == counts.lastIndex) }
        }
        Icon(Icons.chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The first `count` names of the Requests card, then "+N" for the rest; `last` (the last resort) may cut a name short. */
@Composable
private fun Names(requests: List<Room>, shown: Int, marks: Boolean, last: Boolean) {
    val style = MaterialTheme.typography.bodySmall
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        requests.take(shown).forEachIndexed { i, room ->
            if (marks) identityOf(room)?.let { AccountMark(it, 14.dp) }
            Text(room.name + if (i < shown - 1) "," else "", Modifier.then(if (last) Modifier.weight(1f, fill = false) else Modifier),
                style = style, color = grey, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        // The mark keeps "+1" from reading "1+" on a right-to-left phone.
        if (shown < requests.size) Text("\u200E+${requests.size - shown}", style = style.copy(fontFeatureSettings = "tnum"), color = grey, maxLines = 1)
    }
}

/**
 * iOS's `ViewThatFits(in: .horizontal)`: the first of `count` options that fits the width on one line; the last one when
 * none does (it is measured within the width, so it cuts itself short).
 */
@Composable
private fun FirstThatFits(count: Int, option: @Composable (Int) -> Unit) {
    SubcomposeLayout { constraints ->
        var chosen = subcompose(0) { option(0) }.first().measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        var i = 0
        while (chosen.width > constraints.maxWidth && i < count - 1) {
            i++
            val unbounded = if (i == count - 1) constraints.copy(minWidth = 0) else constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
            chosen = subcompose(i) { option(i) }.first().measure(unbounded)
        }
        layout(chosen.width, chosen.height) { chosen.place(0, 0) }
    }
}

/** I1's row: unread dot, 52 dp avatar (with the account's mark in All), name, time, a two-line preview. */
@Composable
fun ChatRow(room: Room, showsMark: Boolean, times: Inbox.TimeFormats, modifier: Modifier = Modifier) {
    val unread = room.isUnread
    val typing = room.typing.any { it != AppModel.ME }
    val preview = Inbox.preview(room, AppModel.ME, if (room.bridge?.isDM == false) AppModel.profiles else emptyMap())
    val time = if (room.lastMessage == null) "" else Inbox.timeLabel(room.lastActivity, System.currentTimeMillis(), ZoneId.systemDefault(), times)
    val identity = identityOf(room)
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // "Kivo Studio, via Tamo account, 2 unread, You sent a reel, 9:41" (DESIGN §3.7).
    val spoken = listOfNotNull(
        room.name, if (AppModel.accounts.size > 1) identity?.let { "via ${it.name} account" } else null,
        maxOf(room.notificationCount, room.highlightCount).let { if (it > 0) "$it unread" else if (room.isMarkedUnread) "marked unread" else null },
        if (typing) "typing" else preview?.text, time,
    ).filter { it.isNotEmpty() }.joinToString(", ")
    Row(
        modifier.fillMaxWidth().heightIn(min = 76.dp).padding(end = 16.dp, top = 10.dp, bottom = 10.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(22.dp).height(52.dp), contentAlignment = Alignment.Center) {
            if (unread) Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.onBackground, CircleShape)) // never coloured
        }
        Box {
            ChatAvatar(room, 52.dp)
            if (showsMark) MarkOnAvatar(identity, 52.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(room.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(time, style = MaterialTheme.typography.bodyMedium, color = if (unread) MaterialTheme.colorScheme.onBackground else secondary)
            }
            val color = if (unread && !typing) MaterialTheme.colorScheme.onBackground else secondary
            when {
                typing -> Text("typing…", style = MaterialTheme.typography.bodyMedium, color = color, fontStyle = FontStyle.Italic)
                preview != null -> Row(verticalAlignment = Alignment.Top) {
                    preview.glyph?.let { Icon(Icons.of(it), null, Modifier.padding(top = 2.dp, end = 4.dp).size(16.dp), tint = color) }
                    Text(preview.text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The first load's rows: grey shapes, no shimmer (DESIGN §14). */
@Composable
private fun PlaceholderRow() {
    val grey = MaterialTheme.colorScheme.surfaceVariant
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 10.dp, bottom = 10.dp).clearAndSetSemantics { }) {
        Box(Modifier.size(52.dp).background(grey, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(140.dp).height(16.dp).background(grey, RoundedCornerShape(4.dp)))
            Box(Modifier.width(220.dp).height(14.dp).background(grey, RoundedCornerShape(4.dp)))
        }
    }
}

/** The row's time in the phone's own format (12 or 24 hours, the locale's order). */
@Composable
fun rememberTimeFormats(): Inbox.TimeFormats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val h24 = DateFormat.is24HourFormat(context)
    return remember(locale, h24) {
        fun best(skeleton: String) = DateFormat.getBestDateTimePattern(locale, skeleton)
        Inbox.TimeFormats(best(if (h24) "Hm" else "hm"), best("EEE"), best("dMMM"), best("dMMMy"), locale)
    }
}
