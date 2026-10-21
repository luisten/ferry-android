package fi.ferry.messenger

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID

/**
 * A voice note being recorded (the iOS app's VoiceRecorder): AAC in an .m4a, 44.1 kHz mono at 64 kbit/s, sent as
 * audio/mp4 with Instagram's voice flag. Under a second isn't sent; five minutes sends itself. Leaving the thread or
 * the app throws it away. Main thread only.
 */
object VoiceRecorder {
    const val MIN_MS = 1_000L
    const val MAX_MS = 300_000L
    /** A press shorter than this is a tap: recording goes on hands-free. */
    const val TAP_MS = 300L

    private var recorder: MediaRecorder? = null
    private var file: File? = null

    var recording by mutableStateOf(false); private set
    /** `SystemClock.elapsedRealtime()` when it started. */
    var startedAt by mutableLongStateOf(0L); private set

    val elapsed: Long get() = if (recording) SystemClock.elapsedRealtime() - startedAt else 0

    /** 0…1, the loudness since the last call (the strip's meter). */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /** False when the microphone can't start (in use, or no permission). */
    fun start(context: Context): Boolean {
        if (recording) return true
        val out = File(File(context.cacheDir, "outgoing").apply { mkdirs() }, "voice-${UUID.randomUUID()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return runCatching {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(64_000)
            r.setMaxDuration(MAX_MS.toInt())
            r.setOutputFile(out.path)
            r.prepare()
            r.start()
            recorder = r
            file = out
            startedAt = SystemClock.elapsedRealtime()
            recording = true
        }.onFailure {
            r.release()
            out.delete()
        }.isSuccess
    }

    /** The note, or null when it's too short (it's thrown away) or the recorder failed. */
    fun finish(): Attachment? {
        val ms = elapsed
        val out = file
        if (!stopRecorder() || out == null) return null
        if (!isLongEnough(ms)) {
            out.delete()
            return null
        }
        return Attachment(out, "audio/mp4", Attachment.Kind.VOICE, 0, 0, ms.coerceAtMost(MAX_MS), null)
    }

    fun cancel() {
        stopRecorder()
        file?.delete()
        file = null
    }

    private fun stopRecorder(): Boolean {
        val r = recorder ?: return false
        recorder = null
        recording = false
        // stop() throws when nothing was written (a very short press): the file is useless then.
        return runCatching { r.stop() }.isSuccess.also { r.release() }
    }

    fun isLongEnough(ms: Long) = ms >= MIN_MS

    /** A press let go after `heldMs`, `recordedMs` into the note: send it, or go on hands-free. */
    fun sendsOnRelease(heldMs: Long, recordedMs: Long) = heldMs >= TAP_MS && isLongEnough(recordedMs)
}
