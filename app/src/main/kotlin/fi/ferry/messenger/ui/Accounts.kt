package fi.ferry.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.model.AccountColor
import fi.ferry.messenger.model.AccountIdentity
import fi.ferry.messenger.model.BridgeAPI
import fi.ferry.messenger.model.BridgeAPI.Login.Health
import fi.ferry.messenger.model.Inbox

/**
 * Name, colour and mark (DESIGN O5, A2; iOS `IdentityEditor`): a live preview of exactly what changes, then the three
 * fields, as sections of a grouped page (the caller's `Column`, with `LocalMarkRing` set to the card). A name of 16
 * characters at most, one of 8 colours (another account's shows its mark and can't be picked), a one-letter or emoji
 * mark; no two accounts share any of them. The caller decides when to save (`AccountIdentity.problems` empty).
 */
@Composable
fun IdentityEditor(id: String, identity: AccountIdentity, login: BridgeAPI.Login?, change: (AccountIdentity) -> Unit) {
    val all = AppModel.identities
    val others = all.filterKeys { it != id }.values.sortedBy { it.order }
    val problems = AccountIdentity.problems(identity, id, all)
    // A chip or a typed name: a mark that was the old name's initial follows the new one.
    fun named(name: String): AccountIdentity {
        val follow = identity.mark == identity.name.trim().firstOrNull()?.uppercase() && name.isNotBlank()
        return identity.copy(name = name, mark = if (follow) AccountIdentity.normalizedMark(name.trim().take(1)) else identity.mark)
    }
    GroupedSection { IdentityPreview(identity, login) }
    val nameProblem = problems.firstOrNull { it.isAboutName }?.message
    GroupedSection("Name", nameProblem) {
        PlainField(identity.name, { change(named(it.take(AccountIdentity.MAX_NAME_LENGTH))) }, "Name",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp), words = true, error = nameProblem)
        RowDivider()
        Chips(AccountIdentity.nameSuggestions(login?.profile?.name, others.map { it.name }.toSet()), identity.name) { change(named(it)) }
    }
    val taken = AccountColor.entries.mapNotNull { color -> others.firstOrNull { it.color == color }?.let { "${color.label} is used by ${it.name}." } }
    GroupedSection("Color", taken.joinToString(" ").ifEmpty { null }) {
        Swatches(identity.color, id) { change(identity.copy(color = it)) }
    }
    val markProblem = problems.firstOrNull { it.isAboutMark }?.message
    GroupedSection("Mark", markProblem ?: "One letter or emoji, shown on every chat from this account.") {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountMark(identity.copy(mark = identity.mark.ifEmpty { "?" }), 30.dp)
            PlainField(identity.mark, { change(identity.copy(mark = AccountIdentity.normalizedMark(it))) }, "Mark", Modifier.width(56.dp),
                error = markProblem)
        }
        RowDivider()
        Chips(AccountIdentity.markSuggestions(identity.name, others.map { it.mark.uppercase() }.toSet()), identity.mark) { change(identity.copy(mark = it)) }
    }
}

/** DESIGN O5's preview card: an inbox row with this account's mark, an outgoing bubble in its colour, the composer. */
@Composable
private fun IdentityPreview(identity: AccountIdentity, login: BridgeAPI.Login?) {
    val fill by animateColorAsState(identity.color.fillColor, tween(250), label = "fill")
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val subheadline = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp)
    Column(
        Modifier.fillMaxWidth().padding(16.dp).clearAndSetSemantics {
            contentDescription = "Preview: chats show the mark ${identity.mark}, your messages are ${identity.color.label}, and the composer says Message as ${identity.name}."
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box { Picture(null, "Maya", 44.dp); MarkOnAvatar(identity, 44.dp, 18.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Maya Chen", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("Sent a reel by @tidepool.films", style = subheadline, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("9:41", style = subheadline, color = secondary)
        }
        Text("On my way!", Modifier.align(Alignment.End).background(fill, RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
            color = Color.White, style = MaterialTheme.typography.bodyLarge)
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AccountAvatar(identity, login?.profile?.avatar, login?.displayName ?: identity.name, 24.dp, mark = true)
            Text("Message as ${identity.name.ifEmpty { "…" }}", color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * 8 swatches, 36 dp in 48 dp targets, 2 rows of 4. Selected: a check and an outer ring 4 dp outside. Taken: the fill
 * at 40% with the owner's mark in the colour's ink; not selectable. A tick of haptics on each change.
 */
@Composable
private fun Swatches(selected: AccountColor, id: String, pick: (AccountColor) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in AccountColor.entries.chunked(4)) Row(Modifier.fillMaxWidth()) {
            for (color in row) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val owner = AccountIdentity.owner(color, id, AppModel.identities)
                val on = color == selected
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .clickable(enabled = owner == null) {
                            if (!on) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            pick(color)
                        }
                        .clearAndSetSemantics {
                            contentDescription = owner?.let { "${color.label}, used by ${it.name}" } ?: color.label
                            role = Role.RadioButton
                            this.selected = on
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Box(Modifier.size(44.dp).border(2.dp, color.fillColor, CircleShape))
                    Box(Modifier.size(36.dp).background(color.fillColor.copy(alpha = if (owner == null) 1f else 0.4f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        when {
                            on -> Icon(Icons.check, null, Modifier.size(18.dp), tint = Color.White)
                            // The owner's mark in the full colour: white on the faded fill couldn't be read.
                            owner != null -> Text(owner.mark, color = color.ink, fontWeight = FontWeight.Bold,
                                style = TextStyle(fontSize = 15.sp, lineHeight = 15.sp))
                        }
                    }
                }
            }
        }
    }
}

/** A scrolling row of suggestion capsules, edge to edge in the card with the first at 16 dp; the current one outlined. */
@Composable
private fun Chips(values: List<String>, selected: String, pick: (String) -> Unit) {
    if (values.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        for (value in values) {
            val on = value == selected
            // A 32 dp capsule in a 48 dp target, 8 dp apart.
            Box(Modifier.padding(horizontal = 4.dp).minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (on) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                        .clickable { pick(value) }.semantics { this.selected = on }
                        .heightIn(min = 32.dp).widthIn(min = 36.dp).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(value, style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)) }
            }
        }
    }
}

/** A borderless one-line field in a card row (iOS's `TextField` in a `Form`); `label` is its placeholder and its name. */
@Composable
private fun PlainField(value: String, change: (String) -> Unit, label: String, modifier: Modifier, words: Boolean = false, error: String? = null) {
    val ink = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.bodyLarge
    BasicTextField(
        value, change,
        modifier.semantics {
            contentDescription = label
            error?.let { error(it) }
        },
        textStyle = style.copy(color = ink), singleLine = true, cursorBrush = SolidColor(ink),
        keyboardOptions = KeyboardOptions(capitalization = if (words) KeyboardCapitalization.Words else KeyboardCapitalization.None,
            autoCorrectEnabled = false),
        decorationBox = { field ->
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
                field()
            }
        },
    )
}

/**
 * One section of a grouped page (iOS's inset-grouped `Form`): a small header, a rounded card, a footer. Rows inside
 * bring their own 16 dp side padding.
 */
@Composable
fun GroupedSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        header?.let {
            Text(it, Modifier.padding(horizontal = 16.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Grouped.card), content = content)
        footer?.let {
            Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A tappable row of a grouped card: a glyph and its words (iOS's `Button` in a `Form`). */
@Composable
fun ActionRow(text: String, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 52.dp)
            .padding(horizontal = 16.dp).alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { Icon(it, null, Modifier.size(22.dp), tint = color) }
        Text(text, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** The line between two rows of a card, from where their text starts. */
@Composable
fun RowDivider(start: Dp = 16.dp) {
    HorizontalDivider(Modifier.padding(start = start), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** An account's state in words, with its glyph (A1, A2). */
@Composable
fun HealthLabel(health: Health) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            health == Health.Connected -> Icon(Icons.check, null, Modifier.size(16.dp), tint = Status.ok)
            health.isBusy -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            health == Health.NeedsLogin -> Icon(Icons.warning, null, Modifier.size(16.dp), tint = Status.warn)
            else -> Icon(Icons.warning, null, Modifier.size(16.dp), tint = Status.error)
        }
        Text(health.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A1: your accounts with their state (and "Log in" when one is signed out), each opening its page (A2), and "Add
 * Instagram account". Touch and hold an account, then drag, to reorder the scope bar (TalkBack: Move up, Move down).
 * Opening it reconnects an account that failed, as on iOS (pulling down closes a sheet here, so no pull to refresh).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsSheet(dismiss: () -> Unit, addAccount: () -> Unit, open: (String) -> Unit, relogin: (String) -> Unit) {
    LaunchedEffect(Unit) { AppModel.restartSync() }
    val accounts = AppModel.orderedAccounts
    // The order while a drag moves it; saved when it's let go.
    var order by remember(accounts.map { it.id }) { mutableStateOf(accounts.map { it.id }) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    fun move(id: String, by: Int) {
        val i = order.indexOf(id)
        if (i + by !in order.indices) return
        order = order.toMutableList().apply { add(i + by, removeAt(i)) }
    }
    val context = LocalContext.current
    // "Ferry Messenger 1.7": the build's own version name.
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName?.let { "Ferry Messenger $it" } }
    // Fully open, as iOS's sheet: the version line at the end is in view.
    ModalBottomSheet(dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Grouped.page) { CompositionLocalProvider(LocalMarkRing provides Grouped.card) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Accounts", Modifier.padding(horizontal = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            GroupedSection("Instagram accounts", if (accounts.size > 1) "Each chat stays tied to the account it belongs to. Touch and hold an account, then drag to reorder the scope bar."
                else "Each chat stays tied to the account it belongs to.") {
            if (AppModel.accounts.isEmpty()) Text("No accounts yet", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (login in order.mapNotNull { id -> accounts.firstOrNull { it.id == id } }) key(login.id) {
                val id = login.id
                val identity = AppModel.identities[login.id]
                val health = login.health
                val name = identity?.name ?: login.displayName
                val lifted = dragging == id
                val reorder = if (accounts.size < 2) Modifier else Modifier
                    .pointerInput(id) {
                        // A row moves past its neighbour once it's half over it; the finger keeps its place on the row.
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = id; dragY = 0f },
                            onDrag = { _, amount ->
                                dragY += amount.y
                                val h = heights[id] ?: 0
                                if (h > 0 && dragY > h / 2 && order.last() != id) { move(id, 1); dragY -= h }
                                if (h > 0 && dragY < -h / 2 && order.first() != id) { move(id, -1); dragY += h }
                            },
                            onDragEnd = { dragging = null; dragY = 0f; AppModel.moveAccounts(order) },
                            onDragCancel = { dragging = null; dragY = 0f; AppModel.moveAccounts(order) },
                        )
                    }
                    .semantics {
                        customActions = listOfNotNull(
                            CustomAccessibilityAction("Move up") { move(id, -1); AppModel.moveAccounts(order); true }.takeIf { order.first() != id },
                            CustomAccessibilityAction("Move down") { move(id, 1); AppModel.moveAccounts(order); true }.takeIf { order.last() != id },
                        )
                    }
                Row(
                    Modifier.zIndex(if (lifted) 1f else 0f).onSizeChanged { heights[id] = it.height }
                        .graphicsLayer { translationY = if (lifted) dragY else 0f }
                        .then(if (lifted) Modifier.shadow(8.dp, RoundedCornerShape(12.dp)) else Modifier)
                        .fillMaxWidth().background(Grouped.card)
                        // The drag inside the click sees the touch first and takes a long press's release: letting go opens nothing.
                        .clickable { open(login.id) }.then(reorder).padding(horizontal = 16.dp, vertical = 10.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccountAvatar(identity, login.profile?.avatar, name, 44.dp, mark = true)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        login.profile?.username?.let { Text("@$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (login.isProfessional) Text("Professional · Primary and General", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HealthLabel(health)
                    }
                    if (health == Health.NeedsLogin) Button({ relogin(login.id) }) { Text("Log in") }
                    else Icon(Icons.chevron, null, tint = MaterialTheme.colorScheme.outline)
                }
                RowDivider(start = 16.dp + 52.dp + 14.dp)
            }
            ActionRow("Add Instagram account", Icons.add, onClick = addAccount)
            }
            version?.let {
                Text(it, Modifier.padding(start = 16.dp, top = 20.dp), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (AppModel.accounts.isNotEmpty() && !AppModel.demo) BackgroundRow()
        }
    } }
}

/**
 * A2 (DESIGN A2, iOS `AccountDetailView`): one account on a grouped page: its row, the identity editor (saved as it's
 * edited, when it can be), its state and since when, the note on encrypted chats, and Remove Account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(id: String, back: () -> Unit, relogin: (String) -> Unit) {
    BackHandler(onBack = back)
    val login = AppModel.accounts.firstOrNull { it.id == id }
    var draft by remember(id) { mutableStateOf(AppModel.identities[id]) }
    var confirmRemove by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Grouped.page,
        topBar = {
            TopAppBar(
                title = { Text(draft?.name ?: login?.displayName ?: "Account", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(back) { Icon(Icons.back, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Grouped.page, scrolledContainerColor = Grouped.page),
            )
        },
    ) { padding ->
        if (login == null) {
            Box(Modifier.padding(padding)) { Empty(Icons.person, "Account removed", "Its chats left Ferry Messenger on this phone.") }
            return@Scaffold
        }
        val identity = draft
        CompositionLocalProvider(LocalMarkRing provides Grouped.card) {
            Column(
                Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                GroupedSection {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AccountAvatar(identity, login.profile?.avatar, identity?.name ?: login.displayName, 56.dp, mark = true)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(login.profile?.username?.let { "@$it" } ?: identity?.name ?: login.displayName, style = MaterialTheme.typography.bodyLarge)
                            HealthLabel(login.health)
                        }
                    }
                }
                if (identity != null) IdentityEditor(id, identity, login) { edited ->
                    draft = edited
                    if (AccountIdentity.problems(edited, id, AppModel.identities).isEmpty()) AppModel.setIdentity(id, edited)
                }
                GroupedSection("Status") {
                    ValueRow("State", login.health.label)
                    login.state?.timestamp?.let { since ->
                        RowDivider()
                        ValueRow("Since", sinceLabel((since * 1000).toLong()))
                    }
                    (login.health as? Health.Error)?.message?.let {
                        RowDivider()
                        Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (login.health == Health.NeedsLogin) {
                        RowDivider()
                        ActionRow("Log in again") { relogin(id) }
                    }
                }
                GroupedSection {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.lock, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Instagram's end-to-end encrypted chats can't be shown here. Open them in Instagram.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                GroupedSection { ActionRow("Remove Account…", color = Status.error) { confirmRemove = true } }
            }
        }
    }
    if (confirmRemove && login != null) {
        val name = AppModel.identities[id]?.name ?: login.displayName
        val handle = login.profile?.username?.let { "@$it" } ?: name
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove $name?") },
            text = { Text("Chats from $handle leave Ferry Messenger on this phone. Your Instagram account and its chats on Instagram aren't affected. To add it back, you'll log in to Instagram again.") },
            confirmButton = { TextButton({ confirmRemove = false; AppModel.removeAccount(id) }) { Text("Remove Account", color = Status.error) } },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** A label and its value on one row of a card (iOS's `LabeledContent`). */
@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "now", "5 minutes ago", "yesterday": iOS's relative date, named. */
private fun sinceLabel(ms: Long): String {
    val now = System.currentTimeMillis()
    if (now - ms < 60_000) return "now"
    return android.text.format.DateUtils.getRelativeTimeSpanString(ms, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
}

/** A3: a signed-out account. Why, that nothing's lost, and logging in again (the web view, as that account). */
@Composable
fun ReloginDialog(id: String, dismiss: () -> Unit, login: () -> Unit) {
    val account = AppModel.accounts.firstOrNull { it.id == id } ?: return dismiss()
    val name = AppModel.identities[id]?.name ?: account.displayName
    val handle = account.profile?.username?.let { "@$it" } ?: name
    val others = AppModel.orderedAccounts.filter { it.id != id && !it.health.needsUser }.map { AppModel.identities[it.id]?.name ?: it.displayName }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Log in to $name again") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Instagram ended the session for $handle. This usually happens after a password change, or when Instagram resets logins.")
                Text("Your chats are safe. Missed messages sync after you log in.")
                Inbox.othersKeepWorking(others)?.let { Text(it) }
            }
        },
        confirmButton = { TextButton({ dismiss(); login() }) { Text("Log in again") } },
        dismissButton = { TextButton(dismiss) { Text("Not now") } },
    )
}

/** DESIGN §7.2's inbox banner: one at a time, the most fundamental first; "Later" puts it away until it changes. */
@Composable
fun ConnectionBanner(banner: Inbox.Banner, openAccounts: () -> Unit, relogin: (String) -> Unit) {
    val account = (banner as? Inbox.Banner.Account)?.let { b -> AppModel.accounts.firstOrNull { it.id == b.id } }
    val name = account?.let { AppModel.identities[it.id]?.name ?: it.displayName } ?: "An account"
    val handle = account?.profile?.username?.let { "@$it" } ?: name
    val (title, body, action) = when {
        banner == Inbox.Banner.Offline -> Triple("Can't reach Instagram", "Retrying. New messages arrive when it's back.", null)
        banner is Inbox.Banner.Accounts -> Triple("${banner.count} accounts need attention", "Open Accounts to see what's wrong.", "Open" to openAccounts)
        account?.health is Health.Error -> Triple("Can't connect $name to Instagram", "Retrying automatically. Your messages are safe.", null)
        account?.health == Health.Reconnecting -> Triple("Reconnecting to $name…", "Instagram dropped the connection. Retrying automatically.", null)
        else -> Triple("$name is signed out", "New messages to $handle aren't arriving.", "Log in" to { account?.let { relogin(it.id) }; Unit })
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.warning, null, Modifier.size(18.dp), tint = if (banner == Inbox.Banner.Offline) MaterialTheme.colorScheme.onSurfaceVariant else Status.warn)
            Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (banner != Inbox.Banner.Offline) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            action?.let { (label, go) -> TextButton(go) { Text(label, fontWeight = FontWeight.SemiBold) } }
            if (banner is Inbox.Banner.Account) TextButton({ AppModel.hiddenBanner = banner }) { Text("Later") }
        }
    }
}

/** T2's signed-out bar: in place of the composer while the chat's account is signed out. */
@Composable
fun RecoveryBar(name: String, login: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$name is signed out. Log in again to send and receive messages.", style = MaterialTheme.typography.bodyMedium)
        Button(login, Modifier.fillMaxWidth()) { Text("Log in again") }
    }
}

/**
 * O7 (DESIGN): once, when there's an account and Android 13+ hasn't been asked yet, why notifications, then the system's
 * own question. "Not now" isn't asked again; Android's settings can turn them on later.
 */
@Composable
fun NotificationsPrompt() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ferry", android.content.Context.MODE_PRIVATE) }
    var asked by remember { mutableStateOf(prefs.getBoolean(ASKED, false)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    if (asked || android.os.Build.VERSION.SDK_INT < 33 || fi.ferry.messenger.Notifier.allowed(context)) return
    fun done() {
        prefs.edit().putBoolean(ASKED, true).apply()
        asked = true
    }
    AlertDialog(
        onDismissRequest = ::done,
        title = { Text("Get notified about new messages?") },
        text = { Text("You'll see which account each message is for. Ferry stays connected in the background for this, with a quiet notification of its own.") },
        confirmButton = { TextButton({ done(); permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("Turn On Notifications") } },
        dismissButton = { TextButton(::done) { Text("Not now") } },
    )
}

private const val ASKED = "notifications.asked"

/**
 * E8's weak spot: Android (and phone makers' savers) may stop Ferry in the background unless it's left out of battery
 * optimisation. One tap asks Android; phone makers' own switches stay the user's (Samsung, Xiaomi).
 */
@Composable
fun BackgroundRow() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(android.os.PowerManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
    }
    if (exempt) return
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).clip(RoundedCornerShape(20.dp)).background(Grouped.card).padding(16.dp)) {
        Text("Android may stop Ferry in the background", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("Then new messages wait until you open it. Let Ferry run in the background to keep them coming.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({
            @android.annotation.SuppressLint("BatteryLife") // not on Play: asking directly is allowed
            val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))
            ask.launch(intent)
        }) { Text("Allow") }
    }
}
