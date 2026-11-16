package fi.ferry.messenger

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.container.Mp4OrientationData
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.max

/** A picked photo, GIF or video, or a recorded voice note, ready to upload: a file of ours with no location in it, and what the echo shows. */
class Attachment(
    val file: File,
    val mimetype: String,
    val kind: Kind,
    val width: Int,
    val height: Int,
    /** Milliseconds, a video's. */
    val duration: Long?,
    /** About 720 px: the composer's and the local echo's picture. */
    val preview: ImageBitmap?,
) {
    enum class Kind { PHOTO, GIF, VIDEO, VOICE }
}

/**
 * Getting a pick ready (the iOS app's PhotoMessage and VideoMessage, with Instagram's limits): photos as JPEG at most
 * 2048 px and 8,000,000 bytes, no metadata copied; GIFs and animated WebPs as they are, cleaned (`MediaClean`), at most
 * 8,000,000; videos through Media3's Transformer with only the orientation kept, as they are up to 25,000,000 bytes,
 * else at 720p, 540p, then 480p. Picked, pasted or taken with the camera, everything goes this way.
 */
object Outgoing {
    private const val MAX_IMAGE = 8_000_000L
    private const val MAX_VIDEO = 25_000_000L
    private val main = Handler(Looper.getMainLooper())
    /** Photos and GIFs one at a time (decision A13): ten 2048 px decodes at once would take ~250 MB. */
    private val photos = Executors.newSingleThreadExecutor()
    /** Videos one at a time (D6): phones have few video encoders. Main thread only. */
    private val videos = ArrayDeque<() -> Unit>()
    private var videoBusy = false

    class Failure(message: String) : Exception(message)

    private fun dir(context: Context) = File(context.cacheDir, "outgoing").apply { mkdirs() }
    private fun cameraDir(context: Context) = File(context.cacheDir, "camera").apply { mkdirs() }

    /** At launch: what a send or the camera left behind more than a day ago. */
    fun sweep(context: Context) = thread(isDaemon = true) {
        val day = System.currentTimeMillis() - 24 * 3600_000L
        listOf(dir(context), cameraDir(context)).flatMap { it.listFiles().orEmpty().toList() }.filter { it.lastModified() < day }.forEach { it.delete() }
    }

    /** Where the camera app puts a photo or video for us (lent to it through `FileProvider`, `shared_files.xml`). */
    fun cameraFile(context: Context, video: Boolean): File = File(cameraDir(context), "camera-${UUID.randomUUID()}.${if (video) "mp4" else "jpg"}")

    /**
     * `done` on the main thread with the attachment, or why it can't be sent, once its turn came (photos one at a
     * time, videos one at a time). `wanted`: still in the composer when its turn comes, else it's never prepared.
     * `copy`: pasted or inserted by the keyboard, so it's read at once, before the paste's read grant can end (decision
     * A46); the copy goes when it's prepared.
     */
    fun prepare(context: Context, uri: Uri, wanted: () -> Boolean = { true }, copy: Boolean = false, done: (Result<Attachment>) -> Unit) {
        val app = context.applicationContext
        val type = app.contentResolver.getType(uri).orEmpty()
        if (!copy) return enqueue(app, uri, type, wanted, done)
        thread {
            val file = runCatching { copyIn(app, uri, type) }.getOrNull()
            main.post {
                if (file == null) done(Result.failure(Failure(if (type.startsWith("video/")) "This video can't be read." else "This photo can't be read.")))
                else enqueue(app, Uri.fromFile(file), type, wanted) { file.delete(); done(it) }
            }
        }
    }

    /** On the main thread. */
    private fun enqueue(context: Context, uri: Uri, type: String, wanted: () -> Boolean, done: (Result<Attachment>) -> Unit) {
        val removed = Result.failure<Attachment>(Failure("Removed."))
        if (type.startsWith("video/")) {
            videos += {
                val finish = { result: Result<Attachment> -> videoBusy = false; done(result); nextVideo() }
                if (wanted()) video(context, uri, type, finish) else finish(removed)
            }
            nextVideo()
        } else photos.execute {
            val result = if (wanted()) runCatching { image(context, uri, type) } else removed
            main.post { done(result) }
        }
    }

    private fun nextVideo() {
        if (videoBusy) return
        val job = videos.removeFirstOrNull() ?: return
        videoBusy = true
        job()
    }

    /** A pasted photo or video into our own folder. Only another app's content, never a file path. */
    private fun copyIn(context: Context, uri: Uri, type: String): File {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        val file = File(dir(context), "in-${UUID.randomUUID()}.${MediaFiles.extension(type)}")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    private fun image(context: Context, uri: Uri, type: String): Attachment {
        val resolver = context.contentResolver
        if ((type == "image/gif" || type == "image/webp") && animated(context, uri)) {
            val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
            val clean = (if (type == "image/gif") MediaClean.gif(bytes) else MediaClean.webp(bytes)) ?: throw Failure("This GIF can't be read.")
            if (clean.size > MAX_IMAGE) throw Failure("This GIF is too large to send to Instagram.")
            val file = File(dir(context), "gif-${UUID.randomUUID()}.${MediaFiles.extension(type)}").apply { writeBytes(clean) }
            val first = BitmapFactory.decodeByteArray(clean, 0, clean.size) ?: throw Failure("This GIF can't be read.")
            return Attachment(file, type, Attachment.Kind.GIF, first.width, first.height, null, scaled(first, 720).asImageBitmap())
        }
        val bitmap = decode(context, uri, 2048) ?: throw Failure("This photo can't be read.")
        // Transparency goes onto white, as JPEG has none.
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        }
        val jpeg = listOf(85, 70, 50).firstNotNullOfOrNull { quality ->
            ByteArrayOutputStream().also { flat.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray().takeIf { it.size <= MAX_IMAGE }
        } ?: throw Failure("This photo is too large to send to Instagram.")
        val file = File(dir(context), "photo-${UUID.randomUUID()}.jpg").apply { writeBytes(jpeg) }
        return Attachment(file, "image/jpeg", Attachment.Kind.PHOTO, flat.width, flat.height, null, scaled(flat, 720).asImageBitmap())
    }

    private fun animated(context: Context, uri: Uri): Boolean = Build.VERSION.SDK_INT >= 28 && runCatching {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, uri)) is AnimatedImageDrawable
    }.getOrDefault(false)

    /** Upright (the EXIF orientation applied) and at most `max` px on its longest side. */
    private fun decode(context: Context, uri: Uri, max: Int): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > max) decoder.setTargetSize(info.size.width * max / longest, info.size.height * max / longest)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val raw = resolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        // ponytail: rotations only; mirrored orientations (2, 4, 5, 7) are rare from cameras and come out unmirrored.
        val orientation = resolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val upright = if (degrees == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        return scaled(upright, max)
    }

    private fun scaled(bitmap: Bitmap, max: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        return if (longest <= max) bitmap else Bitmap.createScaledBitmap(bitmap, bitmap.width * max / longest, bitmap.height * max / longest, true)
    }

    private fun video(context: Context, uri: Uri, type: String, done: (Result<Attachment>) -> Unit) {
        thread {
            val about = runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    val frame = r.getFrameAtTime(0)?.let { scaled(it, 720) }
                    val (dw, dh) = if (rotation == 90 || rotation == 270) h to w else w to h
                    Video(dw, dh, duration, frame?.asImageBitmap())
                }
            }.getOrNull()
            val size = runCatching { context.contentResolver.openFileDescriptor(uri, "r")!!.use { it.statSize } }.getOrDefault(-1L)
            main.post {
                if (about == null) return@post done(Result.failure(Failure("This video can't be read.")))
                // As it is when it's small enough (the metadata still goes), else smaller and smaller.
                val steps = (if ((type == "video/mp4" || type == "video/quicktime") && size in 1..MAX_VIDEO) listOf<Int?>(null) else emptyList()) +
                    listOf(720, 540, 480)
                fit(context, uri, steps, about, done)
            }
        }
    }

    private class Video(val width: Int, val height: Int, val duration: Long?, val preview: ImageBitmap?)

    private fun fit(context: Context, uri: Uri, steps: List<Int?>, about: Video, done: (Result<Attachment>) -> Unit) {
        if (steps.isEmpty()) return done(Result.failure(Failure("This video is too long to send to Instagram. Trim it first.")))
        val step = steps.first() // null: as it is
        val out = File(dir(context), "video-${UUID.randomUUID()}.mp4")
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).apply {
            if (step != null) setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(step))))
        }.build()
        Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            // Only the orientation is written: no location, no XMP, no dates (iOS's metadataItemFilter.forSharing()).
            .setMuxerFactory(InAppMp4Muxer.Factory { entries -> entries.retainAll { it is Mp4OrientationData } })
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (out.length() in 1..MAX_VIDEO) {
                        val (w, h) = if (step == null) about.width to about.height else shortSide(about, step)
                        done(Result.success(Attachment(out, "video/mp4", Attachment.Kind.VIDEO, w, h, about.duration, about.preview)))
                    } else {
                        out.delete()
                        fit(context, uri, steps.drop(1), about, done)
                    }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    out.delete()
                    if (steps.size > 1) fit(context, uri, steps.drop(1), about, done)
                    else done(Result.failure(Failure("This video can't be prepared for Instagram.")))
                }
            })
            .build()
            .start(item, out.path)
    }

    private fun shortSide(about: Video, short: Int): Pair<Int, Int> {
        val s = minOf(about.width, about.height).coerceAtLeast(1)
        return about.width * short / s to about.height * short / s
    }
}
