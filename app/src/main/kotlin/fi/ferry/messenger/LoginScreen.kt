package fi.ferry.messenger

import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import fi.ferry.messenger.ui.Icons

/**
 * O3 (DESIGN O3, iOS `ConnectSheet`): the user logs in on instagram.com by hand; the app never sees the password. Once
 * the session cookies exist and the page says logged in, they go to the engine (`AppModel.login`, as `relogin` for A3)
 * while the page waits under "Connecting…"; [connected] then, or an error with "Try again", which starts over on a fresh
 * page. Android's web view has no private store per view (iOS's `nonPersistent()`): its cookies are app-wide and saved
 * to disk, so they and web storage are cleared before the page loads and again when the view goes, then flushed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(relogin: String?, close: () -> Unit, connected: () -> Unit) {
    var captured by remember { mutableStateOf<Map<String, String>?>(null) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    val submitting = AppModel.connecting
    val haptics = LocalHapticFeedback.current
    fun finish(cookies: Map<String, String>) {
        if (done) return
        done = true
        AppModel.login(cookies, relogin) { failure ->
            if (failure == null) return@login connected()
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            error = failure
        }
    }
    fun evaluate(url: String?) {
        val cookies = sessionCookies()
        when {
            cookies == null -> captured = null
            url != null && loggedIn.matches(url) -> finish(cookies)
            else -> captured = cookies
        }
    }
    LaunchedEffect(captured != null) { if (captured != null) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }

    // Closing mid-submit would leave a login Instagram already accepted with nowhere to land.
    BackHandler { if (!submitting) close() }
    // Re-login names the account: finishing as a different one is refused (`AppModel.login`).
    val title = relogin?.let { id -> AppModel.accounts.firstOrNull { it.id == id }?.profile?.username }?.let { "Log in as @$it" } ?: "Log in to Instagram"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(close, enabled = !submitting) { Icon(Icons.close, "Cancel") } },
                actions = { IconButton({ web?.reload() }, enabled = web != null && !submitting) { Icon(Icons.retry, "Reload") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (error == null) Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().padding(16.dp)) {
                // Instagram's "Save your login info?" page isn't a logged-in URL: don't leave the user stuck there.
                captured?.let { cookies ->
                    Button({ finish(cookies) }, Modifier.fillMaxWidth(), enabled = !submitting) { Text("Logged in. Continue") }
                } ?: Footer()
            }
        },
    ) { padding ->
        val failure = error
        if (failure != null) {
            Failure(failure, Modifier.padding(padding)) {
                // A fresh page: the old view went with the error, cookies and storage cleared.
                error = null; captured = null; done = false; progress = 0
            }
            return@Scaffold
        }
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        // AndroidView's default WRAP_CONTENT makes the page's viewport 0 px tall to CSS (100%, 100dvh):
                        // Instagram's login laid out, invisible.
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(m: ConsoleMessage) = true // the page's console stays out of Logcat
                            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                        }
                        webViewClient = object : WebViewClient() {
                            // Logging out here would end the session the app is about to get.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = isLogout(request.url)
                            override fun onPageFinished(view: WebView, url: String?) = evaluate(url)
                            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = evaluate(url)
                        }
                        clearWebData { loadUrl("https://www.instagram.com/accounts/login/") }
                        web = this
                    }
                },
                onRelease = { view ->
                    web = null
                    view.stopLoading()
                    view.clearCache(true)
                    view.destroy()
                    clearWebData {}
                },
            )
            if (progress in 1..99) LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().align(Alignment.TopCenter))
            if (submitting) Submitting()
        }
    }
}

/** The page's footer while nothing's captured: where the password goes, and what Instagram may ask. */
@Composable
private fun Footer() {
    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.lock, null, Modifier.size(16.dp))
            Text("Your password goes only to Instagram. Ferry Messenger keeps the session on this phone, encrypted.", style = style)
        }
        Text("Instagram may ask you to confirm it's you. Keep your Instagram app or email nearby.", style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The page dims under a card while the engine logs in (IGLogin: Instagram's index, mailbox and socket). */
@Composable
private fun Submitting() {
    Box(
        // Takes every touch: the page underneath waits.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(24.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Text("Connecting…", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** iOS's error view: what Instagram or the engine said, and a fresh start. */
@Composable
private fun Failure(message: String, modifier: Modifier, retry: () -> Unit) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.warning, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text("Couldn't connect", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(retry) { Text("Try again") }
    }
}

// The bridge's login step, as on iOS (InstagramLogin.swift): these cookies, and a page that means logged in.
private val required = listOf("sessionid", "csrftoken", "ds_user_id")
private val optional = listOf("rur", "shbid", "shbts", "mid", "ig_did")
private val loggedIn = Regex("""^https://www\.instagram\.com/(?:direct/(?:inbox/|t/[0-9]+/)?)?(?:\?.*)?$""")

/** The session's cookies, once the required ones are all there. Never logged. */
private fun sessionCookies(): Map<String, String>? {
    val all = CookieManager.getInstance().getCookie("https://www.instagram.com/").orEmpty().split("; ")
        .map { it.split("=", limit = 2) }
        .filter { it.size == 2 && it[1].isNotEmpty() }
        .associate { it[0] to it[1] }
    return all.filterKeys { it in required || it in optional }.takeIf { it.keys.containsAll(required) }
}

private fun isLogout(url: Uri): Boolean {
    val host = url.host ?: return false
    return (host == "instagram.com" || host.endsWith(".instagram.com")) && url.path.orEmpty().startsWith("/accounts/logout")
}

private fun clearWebData(then: () -> Unit) {
    WebStorage.getInstance().deleteAllData()
    CookieManager.getInstance().removeAllCookies {
        CookieManager.getInstance().flush()
        then()
    }
}
