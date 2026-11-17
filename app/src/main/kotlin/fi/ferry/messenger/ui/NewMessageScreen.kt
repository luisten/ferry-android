package fi.ferry.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.model.BridgeAPI
import fi.ferry.messenger.model.Inbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private sealed interface Search {
    data object Idle : Search
    data object Searching : Search
    data object Failed : Search
    data class Done(val contacts: List<BridgeAPI.Contact>) : Search
}

/**
 * N1 New message (DESIGN N1): pick the From account first, then search Instagram *as that account* and open the DM.
 * Choosing the account first means a chat can't start from the wrong one (DESIGN §0). In an account's scope it's
 * preselected, and so is the only account; in All with several nothing is, and the search waits for a chip.
 */
@Composable
fun NewMessageScreen(open: (String) -> Unit, back: () -> Unit) {
    BackHandler(onBack = back)
    val preselected = (AppModel.scope as? Inbox.Scope.Account)?.id ?: AppModel.accounts.singleOrNull()?.id
    var from by rememberSaveable { mutableStateOf(preselected) }
    var query by rememberSaveable { mutableStateOf("") }
    var search by remember { mutableStateOf<Search>(Search.Idle) }
    /** The result being opened (a spinner on its row). */
    var opening by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val trimmed = query.trim()
    val login = AppModel.accounts.firstOrNull { it.id == from }
    val name = from?.let { AppModel.identities[it]?.name } ?: login?.displayName ?: "this account"

    // Each search is an Instagram request: 300 ms after the last keystroke, and not for one letter (DESIGN N1).
    // A new keystroke or account cancels the waiting one.
    LaunchedEffect(from, trimmed) {
        val account = from
        if (account == null || trimmed.length < MIN_QUERY) {
            search = Search.Idle
            return@LaunchedEffect
        }
        search = Search.Searching
        delay(300)
        search = withContext(Dispatchers.IO) { AppModel.searchUsers(trimmed, account) }?.let { Search.Done(it) } ?: Search.Failed
    }
    LaunchedEffect(from) { if (from != null) focus.requestFocus() }

    Scaffold(containerColor = Grouped.page) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(back) { Icon(Icons.back, "Back") }
                Text("New Message", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("From", Modifier.padding(start = 32.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FromChips(from) { from = it }
                // As iOS's form: the field and the results are one card, the footer under them.
                Box(Modifier.padding(horizontal = 16.dp)) {
                    GroupedSection(footer = login?.profile?.username?.let { "Searching Instagram from @$it" }) {
                        TextField(
                            query, { query = it },
                            Modifier.fillMaxWidth().focusRequester(focus),
                            enabled = from != null,
                            singleLine = true,
                            prefix = { Text("To: ", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            placeholder = { Text(if (from == null) "Choose an account first" else "Search Instagram") },
                            trailingIcon = if (search == Search.Searching) ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }) else null,
                            keyboardOptions = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                            ),
                        )
                        when (val s = search) {
                            Search.Idle, Search.Searching -> {}
                            Search.Failed -> { RowDivider(); Note("Couldn't search from $name. Check that it's connected.") }
                            is Search.Done -> if (s.contacts.isEmpty()) { RowDivider(); Note("No Instagram accounts match “$trimmed”") } else {
                                val account = from
                                if (account != null) s.contacts.forEachIndexed { i, contact ->
                                    RowDivider(if (i == 0) 16.dp else 68.dp)
                                    Result(contact, account, opening == contact.id, enabled = opening == null) {
                                        opening = contact.id
                                        AppModel.openDM(contact, account) { room, why ->
                                            opening = null
                                            if (room != null) open(room) else error = why
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    error?.let {
        AlertDialog({ error = null }, { TextButton({ error = null }) { Text("OK") } }, title = { Text("Couldn't open the chat") }, text = { Text(it) })
    }
}

private const val MIN_QUERY = 2

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Result(contact: BridgeAPI.Contact, account: String, opening: Boolean, enabled: Boolean, start: () -> Unit) {
    val existing = Inbox.existingDM(contact.id, account, AppModel.rooms.values) != null
    val shown = contact.name ?: contact.handle ?: contact.id
    val detail = listOfNotNull(contact.handle?.let { "@$it" }, if (existing) "Open chat" else null).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = start).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Picture(contact.avatarURL, shown, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(shown, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (opening) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** DESIGN N1's From row: one chip per account (mark and name), the chosen one filled with its colour like a scope chip. */
@Composable
private fun FromChips(selected: String?, select: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (login in AppModel.orderedAccounts) {
            val identity = AppModel.identities[login.id] ?: continue
            val on = login.id == selected
            Row(
                Modifier.heightIn(min = 44.dp).clip(CircleShape)
                    .background(if (on) identity.color.fillColor else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable { select(login.id) }
                    .clearAndSetSemantics {
                        contentDescription = login.profile?.username?.let { "${identity.name} account, @$it" } ?: "${identity.name} account"
                        role = Role.RadioButton
                        this.selected = on
                    }
                    .padding(start = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountMark(identity, 20.dp, inverted = on)
                Spacer(Modifier.width(6.dp))
                Text(identity.name, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else MaterialTheme.colorScheme.onBackground, maxLines = 1)
                if (login.health.needsUser) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.warning, null, Modifier.size(14.dp), tint = Status.warn)
                }
            }
        }
    }
}
