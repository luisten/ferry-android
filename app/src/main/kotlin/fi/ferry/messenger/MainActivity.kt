package fi.ferry.messenger

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fi.ferry.messenger.ui.AccountsSheet
import fi.ferry.messenger.ui.FerryTheme
import fi.ferry.messenger.ui.FolderScreen
import fi.ferry.messenger.ui.InboxScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds: `--ez demo true` shows made-up data only, and nothing connects (`Demo`).
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        AppModel.start(applicationContext, demo = debuggable && intent.getBooleanExtra("demo", false))
        setContent { FerryTheme { App() } }
    }

    override fun onStop() {
        super.onStop()
        AppModel.save()
    }
}

private sealed interface Screen {
    data object Inbox : Screen
    data class Folder(val folder: fi.ferry.messenger.model.Inbox.Folder, val scope: fi.ferry.messenger.model.Inbox.Scope) : Screen
    data object Login : Screen
}

// ponytail: a stack of screens in state, no navigation library; one when deep links or saved state need it.
@Composable
private fun App() {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Inbox)) }
    var accountsOpen by remember { mutableStateOf(false) }
    fun push(screen: Screen) {
        stack = stack + screen
    }
    fun pop() {
        stack = stack.dropLast(1)
    }

    val screen = stack.last()
    when {
        screen == Screen.Login -> LoginScreen { cookies ->
            pop()
            cookies?.let(AppModel::login)
        }
        AppModel.accounts.isEmpty() -> Welcome { push(Screen.Login) }
        screen is Screen.Folder -> FolderScreen(screen.folder, screen.scope, ::pop)
        else -> InboxScreen(openFolder = { folder, scope -> push(Screen.Folder(folder, scope)) }, openAccounts = { accountsOpen = true })
    }
    if (accountsOpen) AccountsSheet(dismiss = { accountsOpen = false }, addAccount = { accountsOpen = false; push(Screen.Login) })
}

/** No account yet (onboarding comes with A7): log in on instagram.com. */
@Composable
private fun Welcome(login: () -> Unit) {
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Ferry Messenger", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            if (AppModel.connecting) Text("Connecting…")
            AppModel.loginError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(login, enabled = !AppModel.connecting) { Text("Log in to Instagram") }
            Text(
                "Your password goes only to Instagram. Ferry Messenger keeps the session on this phone, encrypted.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

