package fi.ferry.messenger

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.ferry.core.IGCore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Engine.start(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { App() }
        }
    }

    // C6: every Instagram request since launch, per account and operation, each time the app leaves the screen (as
    // iOS logs them on HOME): `adb logcat -s ferry`. Counts and IDs only.
    override fun onStop() {
        super.onStop()
        Engine.save()
        Log.i("ferry", "requests ${IGCore.counts()?.decodeToString()}")
    }
}

@Composable
private fun App() {
    var loggingIn by remember { mutableStateOf(false) }
    if (loggingIn) {
        LoginScreen { cookies ->
            loggingIn = false
            cookies?.let(Engine::login)
        }
    } else {
        Home(onLogin = { loggingIn = true })
    }
}

@Composable
private fun Home(onLogin: () -> Unit) {
    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Ferry Messenger", style = MaterialTheme.typography.headlineMedium)
            for ((id, name) in Engine.usernames) {
                val n = Engine.chats[id] ?: 0
                Text("@$name: $n ${if (n == 1) "chat" else "chats"}", style = MaterialTheme.typography.titleMedium)
            }
            Engine.status?.let { Text(it) }
            Button(onClick = onLogin) { Text("Log in to Instagram") }
            Text(
                "Your password goes only to Instagram. Ferry Messenger keeps the session on this phone, encrypted.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
