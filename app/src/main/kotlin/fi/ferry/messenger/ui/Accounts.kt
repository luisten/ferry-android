package fi.ferry.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.model.AccountColor
import fi.ferry.messenger.model.AccountIdentity
import fi.ferry.messenger.model.BridgeAPI
import fi.ferry.messenger.model.BridgeAPI.Login.Health
import fi.ferry.messenger.model.Inbox

/** O1 (DESIGN §2): no account yet. What Ferry is, and the one way in. */
@Composable
fun Welcome(login: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.weight(1f))
            Text("Ferry", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Text("All your Instagram DMs.\nNone of the feed.", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            for (promise in listOf("Every account, one inbox", "Reels play right in the chat", "No feed, no Explore, no algorithm")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.check, null, Modifier.size(20.dp))
                    Text(promise, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.weight(1f))
            if (AppModel.connecting) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Connecting…", Modifier.padding(start = 10.dp))
            }
            AppModel.loginError?.let { Text("Couldn't connect: $it", color = Status.error, style = MaterialTheme.typography.bodyMedium) }
            Button(login, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !AppModel.connecting) { Text("Connect Instagram account") }
            Text("Your password goes only to Instagram. Ferry Messenger keeps the session on this phone, encrypted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Not affiliated with Instagram or Meta.", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/**
 * O5 "Name this account" (DESIGN §3.1), right after a new account connects; for the first one, O6 then offers a
 * second. `done(addAnother)`.
 */
@Composable
fun NameAccountScreen(id: String, done: (addAnother: Boolean) -> Unit) {
    val login = AppModel.accounts.firstOrNull { it.id == id }
    val start = AppModel.identities[id]
    var draft by remember(id) { mutableStateOf(start) }
    var offerAnother by remember { mutableStateOf(false) }
    val first = AppModel.accounts.size <= 1
    BackHandler { done(false) }
    if (offerAnother) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
                Text("Add another account?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Connect a second Instagram account. Each chat stays tied to the account it belongs to.", style = MaterialTheme.typography.bodyLarge)
                Button({ done(true) }, Modifier.fillMaxWidth()) { Text("Connect another account") }
                TextButton({ done(false) }, Modifier.fillMaxWidth()) { Text("Not now") }
            }
        }
        return
    }
    val identity = draft ?: return done(false)
    val problems = AccountIdentity.problems(identity, id, AppModel.identities)
    fun save(): Boolean {
        if (problems.isNotEmpty()) return false
        AppModel.setIdentity(id, identity)
        return true
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Name this account", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                TextButton({ if (save()) { if (first) offerAnother = true else done(false) } }, enabled = problems.isEmpty()) { Text("Save") }
            }
            Text("Pick a name, color and mark for ${login?.profile?.username?.let { "@$it" } ?: "this account"}. You'll see them on every chat from this account.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IdentityEditor(id, identity, login) { draft = it }
            if (first) OutlinedButton({ if (save()) done(true) }, Modifier.fillMaxWidth(), enabled = problems.isEmpty()) { Text("Add another account") }
        }
    }
}

/**
 * DESIGN §3.1's editor: a name (16 characters, chips), one of 8 colours (another account's shows its mark and can't
 * be picked) and a mark (one letter or emoji, chips). No two accounts share any of them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IdentityEditor(id: String, identity: AccountIdentity, login: BridgeAPI.Login?, change: (AccountIdentity) -> Unit) {
    val all = AppModel.identities
    val others = all.filterKeys { it != id }.values
    val problems = AccountIdentity.problems(identity, id, all)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Name")
        OutlinedTextField(identity.name, { text ->
            val name = text.take(AccountIdentity.MAX_NAME_LENGTH)
            // A chip or a typed name: a mark that was the old name's initial follows the new one.
            val follow = identity.mark == identity.name.trim().firstOrNull()?.uppercase() && name.isNotBlank()
            change(identity.copy(name = name, mark = if (follow) AccountIdentity.normalizedMark(name.trim().take(1)) else identity.mark))
        }, Modifier.fillMaxWidth(), singleLine = true, isError = problems.any { it.isAboutName },
            supportingText = problems.firstOrNull { it.isAboutName }?.let { { Text(it.message) } })
        Chips(AccountIdentity.nameSuggestions(login?.profile?.name, others.map { it.name }.toSet())) { name ->
            val follow = identity.mark == identity.name.trim().firstOrNull()?.uppercase()
            change(identity.copy(name = name, mark = if (follow) AccountIdentity.normalizedMark(name.take(1)) else identity.mark))
        }
        Label("Color")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (color in AccountColor.entries) {
                val owner = AccountIdentity.owner(color, id, all)
                val on = identity.color == color
                Box(
                    Modifier.size(48.dp).clip(CircleShape).alpha(if (owner != null) 0.4f else 1f)
                        .background(color.fillColor)
                        .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.onBackground, CircleShape) else Modifier)
                        .clickable(enabled = owner == null) { change(identity.copy(color = color)) }
                        .clearAndSetSemantics {
                            contentDescription = owner?.let { "${color.label}, used by ${it.name}" } ?: color.label
                            role = Role.RadioButton
                            selected = on
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        owner != null -> Text(owner.mark, color = Color.White, fontWeight = FontWeight.Bold)
                        on -> Icon(Icons.check, null, tint = Color.White)
                    }
                }
            }
        }
        val colorOwner = AccountIdentity.owner(identity.color, id, all)
        Text(colorOwner?.let { "${identity.color.label} is used by ${it.name}." } ?: identity.color.label,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Label("Mark")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountMark(identity.copy(mark = identity.mark.ifEmpty { "?" }), 40.dp)
            OutlinedTextField(identity.mark, { change(identity.copy(mark = AccountIdentity.normalizedMark(it))) }, Modifier.width(96.dp),
                singleLine = true, isError = problems.any { it.isAboutMark })
        }
        Chips(AccountIdentity.markSuggestions(identity.name, others.map { it.mark.uppercase() }.toSet())) { change(identity.copy(mark = it)) }
        Text(problems.firstOrNull { it.isAboutMark }?.message ?: "One letter or emoji, shown on every chat from this account.",
            style = MaterialTheme.typography.bodySmall,
            color = if (problems.any { it.isAboutMark }) Status.error else MaterialTheme.colorScheme.onSurfaceVariant)
        problems.firstOrNull { it is AccountIdentity.Problem.ColorTaken }?.let { Text(it.message, color = Status.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(top = 6.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Chips(values: List<String>, pick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (value in values) {
            Text(value, Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).clickable { pick(value) }
                .heightIn(min = 36.dp).padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
    }
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
 * Instagram account". ponytail: no drag to reorder the scope bar yet (Compose has no reorderable list built in).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsSheet(dismiss: () -> Unit, addAccount: () -> Unit, open: (String) -> Unit, relogin: (String) -> Unit) {
    ModalBottomSheet(dismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Instagram accounts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (AppModel.accounts.isEmpty()) Text("No accounts yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (login in AppModel.orderedAccounts) {
                val identity = AppModel.identities[login.id]
                val health = login.health
                val name = identity?.name ?: login.displayName
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open(login.id) }.padding(vertical = 8.dp)
                        .semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box { AccountAvatar(identity, login.profile?.avatar, name, 44.dp); if (identity != null) Box(Modifier.align(Alignment.BottomEnd)) { AccountMark(identity, 18.dp, ring = 2.dp) } }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        login.profile?.username?.let { Text("@$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (login.isProfessional) Text("Professional · Primary and General", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HealthLabel(health)
                    }
                    if (health == Health.NeedsLogin) TextButton({ relogin(login.id) }) { Text("Log in") }
                    else Icon(Icons.chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(addAccount, Modifier.fillMaxWidth()) { Text("Add Instagram account") }
            Text("Each chat stays tied to the account it belongs to.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (AppModel.accounts.isNotEmpty() && !AppModel.demo) BackgroundRow()
        }
    }
}

/** A2: one account. Its identity (saved as it's edited, when it can be), its state, and Remove Account. */
@Composable
fun AccountScreen(id: String, back: () -> Unit, relogin: (String) -> Unit) {
    BackHandler(onBack = back)
    val login = AppModel.accounts.firstOrNull { it.id == id }
    var draft by remember(id) { mutableStateOf(AppModel.identities[id]) }
    var confirmRemove by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(back) { Icon(Icons.back, "Back") }
                Text(draft?.name ?: login?.displayName ?: "Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (login == null) {
                Empty(Icons.person, "Account removed", "Its chats left Ferry Messenger on this phone.")
                return@Column
            }
            val identity = draft
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccountAvatar(identity, login.profile?.avatar, identity?.name ?: login.displayName, 56.dp)
                    Column(Modifier.padding(start = 12.dp)) {
                        login.profile?.username?.let { Text("@$it", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                        HealthLabel(login.health)
                    }
                }
                if (identity != null) IdentityEditor(id, identity, login) { edited ->
                    draft = edited
                    if (AccountIdentity.problems(edited, id, AppModel.identities).isEmpty()) AppModel.setIdentity(id, edited)
                }
                HorizontalDivider()
                Label("Status")
                Text("State: ${login.health.label}", style = MaterialTheme.typography.bodyMedium)
                (login.health as? Health.Error)?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Status.error) }
                if (login.health == Health.NeedsLogin) Button({ relogin(id) }) { Text("Log in again") }
                Text("Instagram's end-to-end encrypted chats can't be shown here. Open them in Instagram.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                TextButton({ confirmRemove = true }) { Text("Remove Account…", color = Status.error) }
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
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
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
