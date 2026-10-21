package fi.ferry.messenger

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Voice notes (the iOS app's VoicePlayer): one at a time, so starting another stops the one playing; 1×, 1.5×, 2× carries
 * over to the next; at the end it goes back to the start, paused. Main thread only.
 */
object VoicePlayer {
    private var player: ExoPlayer? = null

    /** The message whose note is loaded, playing or paused. */
    var current by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set
    var speed by mutableStateOf(1f); private set

    val position: Long get() = player?.currentPosition ?: 0

    fun toggle(context: Context, id: String, file: File) {
        val p = player(context)
        if (current == id) {
            if (p.isPlaying) p.pause() else p.play()
            return
        }
        current = id
        p.setMediaItem(MediaItem.fromUri(file.toURI().toString()))
        p.prepare()
        p.play()
    }

    fun seek(id: String, ms: Long) {
        if (current == id) player?.seekTo(ms)
    }

    fun cycleSpeed() {
        speed = when (speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        player?.playbackParameters = PlaybackParameters(speed)
    }

    /** Before a video plays, or when the thread goes. */
    fun stop() {
        player?.stop()
        current = null
    }

    private fun player(context: Context): ExoPlayer = player ?: ExoPlayer.Builder(context.applicationContext)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .build().also { p ->
            p.playbackParameters = PlaybackParameters(speed)
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        p.pause()
                        p.seekTo(0)
                    }
                }
            })
            player = p
        }
}
