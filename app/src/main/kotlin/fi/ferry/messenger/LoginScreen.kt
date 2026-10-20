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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * O3: the user logs in on instagram.com by hand; the app never sees the password. The session cookies go to
 * [onDone] once they exist and the page says logged in (null: cancelled). Android's web view has no private store per
 * view (iOS's `nonPersistent()`): its cookies are app-wide and saved to disk, so they and web storage are cleared
 * before the page loads and again when the view goes, then flushed.
 */
@Composable
fun LoginScreen(onDone: (Map<String, String>?) -> Unit) {
    var captured by remember { mutableStateOf<Map<String, String>?>(null) }
    var done by remember { mutableStateOf(false) }
    fun finish(cookies: Map<String, String>?) {
        if (!done) { done = true; onDone(cookies) }
    }
    fun evaluate(url: String?) {
        val cookies = sessionCookies()
        when {
            cookies == null -> captured = null
            url != null && loggedIn.matches(url) -> finish(cookies)
            else -> captured = cookies
        }
    }

    BackHandler { finish(null) }
    Scaffold(bottomBar = {
        // Instagram's "Save your login info?" page isn't a logged-in URL: don't leave the user stuck there.
        captured?.let { cookies ->
            Button({ finish(cookies) }, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                Text("Logged in. Continue")
            }
        }
    }) { padding ->
        AndroidView(
            modifier = Modifier.padding(padding).imePadding().fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    // AndroidView's default WRAP_CONTENT makes the page's viewport 0 px tall to CSS (100%, 100dvh):
                    // Instagram's login laid out, invisible.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage) = true // the page's console stays out of Logcat
                    }
                    webViewClient = object : WebViewClient() {
                        // Logging out here would end the session the app is about to get.
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = isLogout(request.url)
                        override fun onPageFinished(view: WebView, url: String?) = evaluate(url)
                        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = evaluate(url)
                    }
                    clearWebData { loadUrl("https://www.instagram.com/accounts/login/") }
                }
            },
            onRelease = { view ->
                view.stopLoading()
                view.clearCache(true)
                view.destroy()
                clearWebData {}
            },
        )
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
