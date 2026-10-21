package fi.ferry.messenger

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Full media files (the iOS app's MediaCache): downloaded once into the cache folder under Instagram's file name, https
 * only, no cookies (Instagram's CDN links are signed). Thumbnails stay in memory (`ui.Pictures`). Over 500 MB, the
 * least recently used go until 400 MB are left.
 */
object MediaFiles {
    private lateinit var dir: File
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<File?>>()
    private val downloads = Executors.newFixedThreadPool(3)

    /** Downloads under way: URL → 0…1 (the screens' rings). */
    val progress = mutableStateMapOf<String, Float>()

    fun init(context: Context) {
        dir = File(context.cacheDir, "media").apply { mkdirs() }
        thread(isDaemon = true) { trim() }
    }

    /** The file for `url` when it's here already. */
    fun cached(url: String, mimetype: String?): File? = file(url, mimetype).takeIf { it.isFile }

    /** A picture's file when it's here already, whatever its type: a thumbnail needs no download then. */
    fun cachedImage(url: String): File? =
        listOf("image/jpeg", "image/png", "image/webp", "image/gif").firstNotNullOfOrNull { cached(url, it) }

    /** The file for `url`, downloaded if needed; one download per URL however many ask. Blocking: off the main thread. */
    fun download(url: String, mimetype: String?): File? {
        cached(url, mimetype)?.let { it.setLastModified(System.currentTimeMillis()); return it }
        if (!url.startsWith("https://")) return null
        return inFlight.computeIfAbsent(url) { CompletableFuture.supplyAsync({ fetch(url, file(url, mimetype)) }, downloads) }
            .whenComplete { _, _ -> inFlight.remove(url) }.get()
    }

    /** A file of ours (a photo we're sending) under Instagram's link once the echo brings it, so it isn't fetched back. */
    fun adopt(local: File, url: String, mimetype: String?) {
        if (url.startsWith("https://") && local.isFile) local.copyTo(file(url, mimetype), overwrite = true)
    }

    private fun fetch(url: String, target: File): File? {
        val part = File(target.path + ".part")
        val result = runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            try {
                if (connection.responseCode != 200) return@runCatching null
                val total = connection.contentLengthLong
                var done = 0L
                var shown = -1
                connection.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            val percent = if (total > 0) (done * 100 / total).toInt() else 0
                            if (percent != shown) { // 1 % steps, as on iOS
                                shown = percent
                                main.post { progress[url] = percent / 100f }
                            }
                        }
                    }
                }
                if (total > 0 && done != total) null else part.renameTo(target).let { target }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        part.delete()
        main.post { progress.remove(url) }
        if (result != null) thread(isDaemon = true) { trim() }
        return result
    }

    private fun file(url: String, mimetype: String?): File = File(dir, fileName(url, mimetype))

    // ponytail: the trim walks the folder each time; an index if it ever holds thousands of files.
    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var size = files.sumOf { it.length() }
        if (size <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (size <= TRIM_TO) break
            size -= f.length()
            f.delete()
        }
    }

    /** Instagram's own file name (the CDN path's last part, without its extension) plus one from the mimetype. */
    fun fileName(url: String, mimetype: String?): String {
        val last = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifEmpty { url.hashCode().toUInt().toString() }
        return last.replace(Regex("[^A-Za-z0-9_-]"), "_").take(120) + "." + extension(mimetype)
    }

    fun extension(mimetype: String?): String = when (mimetype) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/mpeg" -> "mp3"
        else -> "bin"
    }

    private const val MAX_BYTES = 500L * 1024 * 1024
    private const val TRIM_TO = 400L * 1024 * 1024
}
