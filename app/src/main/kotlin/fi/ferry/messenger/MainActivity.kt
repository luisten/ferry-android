package fi.ferry.messenger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import fi.ferry.messenger.ui.AccountScreen
import fi.ferry.messenger.ui.AccountsSheet
import fi.ferry.messenger.ui.FerryTheme
import fi.ferry.messenger.ui.FolderScreen
import fi.ferry.messenger.ui.InboxScreen
import fi.ferry.messenger.ui.NameAccountScreen
import fi.ferry.messenger.ui.NewMessageScreen
import fi.ferry.messenger.ui.NotificationsPrompt
import fi.ferry.messenger.ui.ReloginDialog
import fi.ferry.messenger.ui.ThreadScreen
import fi.ferry.messenger.ui.Welcome
import java.io.File

class MainActivity : ComponentActivity() {
    companion object {
        /** A notification's chat. */
        const val OPEN_ROOM = "fi.ferry.messenger.room"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds: `--ez demo true` shows made-up data only, and nothing connects (`Demo`); `--es request_room <room>`
        // has the engine treat that real chat as a request and hold back its sends (iOS's FERRY_DEBUG_AS_REQUEST).
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // With the demo, `--ez signed_out true` signs Kivo Studio out and `--ez naming true` opens O5 for Tamo Veska;
        // `--ez incoming true` and `--ez reaction true` bring a message and a reaction 10 s in, `--ez busy true` many
        // new messages in Harbor Coffee, `--ei group_older <n>` has the group page back from five messages (n pages
        // of 20 made-up ones after its own), `--ei group_extra <n>` adds n unread messages to the group (see `Demo`),
        // `--ez clipboard true` puts two photos on the clipboard to paste into a chat's field (iOS 1.7's paste).
        val demo = debuggable && intent.getBooleanExtra("demo", false)
        AppModel.start(applicationContext, demo, requestRoom = intent.getStringExtra("request_room")?.takeIf { debuggable },
            demoSignedOut = demo && intent.getBooleanExtra("signed_out", false),
            // A live account shown signed out (nothing reaches Instagram) until it logs in again: A3's live check.
            signedOut = intent.getStringExtra("signed_out_account")?.takeIf { debuggable && !demo },
            demoIncoming = demo && intent.getBooleanExtra("incoming", false),
            demoBusy = demo && intent.getBooleanExtra("busy", false),
            demoReaction = demo && intent.getBooleanExtra("reaction", false),
            demoGroupOlder = if (demo) intent.getIntExtra("group_older", 0) else 0,
            demoGroupExtra = if (demo) intent.getIntExtra("group_extra", 0) else 0)
        AppModel.openRoom = intent.getStringExtra(OPEN_ROOM)
        if (demo && intent.getBooleanExtra("naming", false)) AppModel.naming = Demo.TAMO
        if (demo && intent.getBooleanExtra("clipboard", false)) demoClipboard()
        setContent { FerryTheme { App() } }
    }

    /** Two made-up 600 × 400 photos, one colour each, in the outgoing folder and on the clipboard as one clip. */
    private fun demoClipboard() {
        val uris = listOf(0xFF2F80ED.toInt(), 0xFFF2994A.toInt()).mapIndexed { i, colour ->
            val file = File(File(cacheDir, "outgoing").apply { mkdirs() }, "demo-paste-${i + 1}.jpg")
            val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            FileProvider.getUriForFile(this, "$packageName.files", file)
        }
        val clip = ClipData("Demo photos", arrayOf("image/jpeg"), ClipData.Item(uris[0])).apply { addItem(ClipData.Item(uris[1])) }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    /** A notification tapped while Ferry was open (`singleTop`). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(OPEN_ROOM)?.let { AppModel.openRoom = it }
    }

    override fun onStart() {
        super.onStart()
        AppModel.foreground = true
    }

    override fun onStop() {
        super.onStop()
        AppModel.foreground = false
        VoiceRecorder.cancel() // a note isn't recorded in the background (as on iOS)
        VoicePlayer.stop()
        AppModel.save()
    }
}

private sealed interface Screen {
    data object Inbox : Screen
    data class Folder(val folder: fi.ferry.messenger.model.Inbox.Folder, val scope: fi.ferry.messenger.model.Inbox.Scope) : Screen
    data class Thread(val room: String) : Screen
    /** O3, or A3 for `relogin`. */
    data class Login(val relogin: String? = null) : Screen
    data class Account(val id: String) : Screen
    data object NewMessage : Screen
}

// ponytail: a stack of screens in state, no navigation library; one when deep links or saved state need it.
@Composable
private fun App() {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Inbox)) }
    // A notification's chat, over the inbox.
    val openRoom = AppModel.openRoom
    LaunchedEffect(openRoom) {
        if (openRoom != null) {
            stack = listOf(Screen.Inbox, Screen.Thread(openRoom))
            AppModel.openRoom = null
        }
    }
    var accountsOpen by remember { mutableStateOf(false) }
    var reloginOf by remember { mutableStateOf<String?>(null) }
    fun push(screen: Screen) {
        stack = stack + screen
    }
    fun pop() {
        stack = stack.dropLast(1)
    }

    val screen = stack.last()
    val naming = AppModel.naming
    when {
        screen is Screen.Login -> LoginScreen { cookies ->
            pop()
            cookies?.let { AppModel.login(it, screen.relogin) }
        }
        // O5, then O6: a new account gets its name, colour and mark before anything else.
        naming != null && naming in AppModel.loginIDs -> NameAccountScreen(naming) { addAnother ->
            AppModel.naming = null
            if (addAnother) push(Screen.Login())
        }
        // The last account removed: what was open goes with it.
        AppModel.accounts.isEmpty() -> Welcome { stack = listOf(Screen.Inbox, Screen.Login()) }
        screen is Screen.Account -> AccountScreen(screen.id, ::pop) { reloginOf = it }
        screen is Screen.Thread -> ThreadScreen(screen.room, ::pop) { reloginOf = it }
        screen is Screen.Folder -> FolderScreen(screen.folder, screen.scope, { push(Screen.Thread(it)) }, ::pop)
        // The chat it opens takes its place, ready to type (DESIGN N1).
        screen == Screen.NewMessage -> NewMessageScreen({ AppModel.focusComposer = it; stack = stack.dropLast(1) + Screen.Thread(it) }, ::pop)
        else -> InboxScreen(
            openThread = { push(Screen.Thread(it)) },
            openFolder = { folder, scope -> push(Screen.Folder(folder, scope)) },
            openAccounts = { accountsOpen = true },
            relogin = { reloginOf = it },
            newMessage = { push(Screen.NewMessage) },
        )
    }
    if (accountsOpen) AccountsSheet(
        dismiss = { accountsOpen = false },
        addAccount = { accountsOpen = false; push(Screen.Login()) },
        open = { accountsOpen = false; push(Screen.Account(it)) },
        relogin = { accountsOpen = false; reloginOf = it },
    )
    reloginOf?.let { id -> ReloginDialog(id, dismiss = { reloginOf = null }) { push(Screen.Login(relogin = id)) } }
    if (AppModel.accounts.isNotEmpty() && naming == null) NotificationsPrompt()
}
