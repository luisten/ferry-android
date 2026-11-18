package fi.ferry.messenger

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * In-app updates (A79 to A84). At most once a day, when Ferry comes on screen (never in the background or the demo),
 * one HEAD request to GitHub's "latest release" link: its redirect names the tag. A newer version shows the inbox's
 * banner; Update streams that release's APK into a `PackageInstaller` session. Android installs it only over the same
 * signing key, so nothing else is checked; where it allows (Android 12+, an app updating itself) without its dialog.
 */
object Updates {
    /** A newer release's version ("1.7.2"), from the last check (kept, so a relaunch shows it before the next one). */
    var available by mutableStateOf<String?>(null); private set
    /** The version "Later" put away, until the next launch (as `AppModel.hiddenBanner`). */
    var hidden by mutableStateOf<String?>(null)
    /** Accounts' "Check for updates" (on by default). */
    var enabled by mutableStateOf(true); private set
    /** The APK downloading (0…1; 1 while Android installs it); null when no update is under way. */
    var progress by mutableStateOf<Float?>(null); private set
    /** Why the last Update failed, in plain words. */
    var error by mutableStateOf<String?>(null); private set
    /** Android's own "Update" dialog, for the activity on screen to start (`STATUS_PENDING_USER_ACTION`). */
    var confirm by mutableStateOf<Intent?>(null)

    /** The banner's version: newer, not put away, checks on. */
    val banner: String? get() = available?.takeIf { enabled && it != hidden }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var prefs: SharedPreferences? = null

    /** `MainActivity.onStart`. `force` (the demo's `--ez update true`): now, in the demo too. */
    fun check(context: Context, force: Boolean = false) {
        val prefs = prefs(context)
        if (!enabled || AppModel.demo && !force) return
        val current = versionName(context)
        if (available == null) available = prefs.getString(LATEST, null)?.takeIf { newer(it, current) }
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(CHECKED, 0) in 0 until DAY) return
        if (!AppModel.demo) prefs.edit().putLong(CHECKED, now).apply() // a failed check waits a day too
        thread(isDaemon = true) {
            val version = runCatching { latest(current) }.getOrNull() ?: return@thread
            if (!AppModel.demo) prefs.edit().putString(LATEST, version).apply()
            if (newer(version, current)) main.post { available = version }
        }
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        if (!AppModel.demo) prefs(context).edit().putBoolean(ENABLED, on).apply()
    }

    /** Update (the banner, once Android lets Ferry install apps): download, then hand it to Android. */
    fun install(context: Context) {
        val version = available ?: return
        if (progress != null) return
        error = null
        progress = 0f
        val app = context.applicationContext
        thread(isDaemon = true) {
            try {
                download(app, version)
                main.post { progress = 1f }
            } catch (_: Exception) {
                main.post { fail("Couldn't download the update. Check your connection and try again.") }
            }
        }
    }

    /** `UpdateReceiver`: Android's answer. Success replaces this process, so it's rarely heard. */
    fun result(status: Int, intent: Intent) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> progress = null // installed, or Cancel in Android's dialog
            // A signature that doesn't match the installed app's is Android's INSTALL_FAILED_UPDATE_INCOMPATIBLE, a conflict
            // (STATUS_FAILURE_INCOMPATIBLE is a version or a phone the APK doesn't fit).
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                fail("The update wasn't installed: it was signed with a different key than this Ferry Messenger.")
            else -> fail("The update wasn't installed. " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty())
        }
    }

    private fun fail(message: String) {
        progress = null
        error = message.trim()
    }

    private fun prefs(context: Context): SharedPreferences = prefs ?: context.getSharedPreferences("ferry", Context.MODE_PRIVATE).also {
        prefs = it
        enabled = it.getBoolean(ENABLED, true)
    }

    private fun versionName(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** The latest release's version, from where GitHub's "latest" link redirects to; null when it says nothing usable. */
    private fun latest(current: String): String? {
        val connection = URL(LATEST_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        connection.setRequestProperty("User-Agent", "FerryMessenger/$current") // nothing else about the phone or the user
        try {
            return tag(connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }

    /** The APK straight into an install session (no file of ours), then committed with `UpdateReceiver` for the answer. */
    private fun download(context: Context, version: String) {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } } // one a closed Ferry left
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                val connection = URL(apkURL(version)).openConnection() as HttpURLConnection // GitHub redirects to its file host
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "FerryMessenger/${versionName(context)}")
                try {
                    if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
                    val total = connection.contentLengthLong
                    var done = 0L
                    var shown = -1
                    session.openWrite("ferry-messenger-$version.apk", 0, total).use { output ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                done += n
                                val percent = if (total > 0) (done * 100 / total).toInt() else 0
                                if (percent != shown) { // 1 % steps, as the media downloads
                                    shown = percent
                                    main.post { progress = percent.coerceAtMost(99) / 100f }
                                }
                            }
                        }
                        session.fsync(output)
                    }
                    if (total > 0 && done != total) throw IOException("cut short")
                } finally {
                    connection.disconnect()
                }
                // Mutable: the installer adds its status (required to say so from Android 12, which targetSdk 31+ enforces).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** "…/releases/tag/v1.7.2" → "1.7.2"; null for anything else (no release yet redirects to "…/releases"). */
    fun tag(location: String?): String? =
        location?.substringAfter("/releases/tag/", "")?.removePrefix("v")?.takeIf { parts(it) != null }

    /** Whether `version` is newer than `current`, number by number, a missing one 0 ("1.7" = "1.7.0" < "1.7.0.1"). */
    fun newer(version: String, current: String): Boolean {
        val a = parts(version) ?: return false
        val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    /** The release's APK; its file name is load-bearing (A83). */
    fun apkURL(version: String) = "$REPO/releases/download/v$version/ferry-messenger-$version.apk"

    private fun parts(version: String): List<Int>? =
        version.removePrefix("v").split('.').map { p -> p.toIntOrNull()?.takeIf { it >= 0 } ?: return null }

    private const val REPO = "https://github.com/luisten/ferry-android"
    private const val LATEST_URL = "$REPO/releases/latest"
    private const val TIMEOUT = 10_000
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val ENABLED = "updates.check"
    private const val CHECKED = "updates.checked"
    private const val LATEST = "updates.latest"
}

/** The install session's answer (an explicit intent, so not exported). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Updates.result(intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE), intent)
    }
}
