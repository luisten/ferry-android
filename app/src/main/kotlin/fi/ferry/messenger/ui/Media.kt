package fi.ferry.messenger.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.MediaFiles
import fi.ferry.messenger.VoicePlayer
import fi.ferry.messenger.model.MediaInfo
import fi.ferry.messenger.model.Message
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.MessageKind.ShareState
import fi.ferry.messenger.model.MessageKind.ShareType
import fi.ferry.messenger.model.Room
import fi.ferry.messenger.model.ShareCaption
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What a full-screen viewer shows: a photo (a GIF plays there), or a video. */
sealed interface Viewer {
    data class Photo(val file: File, val animated: Boolean) : Viewer
    data class Video(val file: File) : Viewer
}

/** What the thread lends its media cards: its room (for links and "Load video"), whether GIFs may play, the viewer. */
class MediaContext(val room: Room, val autoplay: Boolean, val open: (Viewer) -> Unit)

val LocalMedia = compositionLocalOf<MediaContext?> { null }

/**
 * A card's tap, run by the message's own gesture handler (`MessageMenu`), so a long press on a photo still opens the
 * menu: a card with a clickable of its own would take the whole gesture. One that needs its own (each card of an
 * opened set) gets the message's long press as `hold`.
 */
class TapTarget {
    var action: (() -> Unit)? = null
    var hold: (() -> Unit)? = null
    /** The message's own tap and double tap (a heart, when it may get one), for a text with links that takes its touches. */
    var click: () -> Unit = {}
    var heart: (() -> Unit)? = null
}

val LocalTap = compositionLocalOf<TapTarget?> { null }

/** A card's own tap (in an opened set, a link's preview): holding it still opens the message's sheet, as everywhere in a bubble. */
@Composable
fun Modifier.ownTap(action: () -> Unit): Modifier {
    val hold = LocalTap.current?.hold
    return combinedClickable(onLongClickLabel = hold?.let { "React or reply" }, onLongClick = hold, onClick = action)
}

@Composable
private fun OnTap(action: () -> Unit) {
    val target = LocalTap.current
    SideEffect { target?.action = action }
}

private val main = Handler(Looper.getMainLooper())

/**
 * A card's file, then `done` on the main thread. Not in the row's scope: a row scrolled away would cancel it. A failed
 * download reads the thread again, since Instagram's links expire (33 h to 30 days); the next tap uses the new one.
 */
private fun fetch(url: String, mimetype: String?, room: Room, failed: () -> Unit, done: (File) -> Unit) {
    AppModel.localFile(url)?.let { return done(it) } // ours, still on its way
    thread {
        val file = MediaFiles.download(url, mimetype)
        main.post {
            if (file != null) done(file) else {
                failed()
                AppModel.refreshMedia(room)
            }
        }
    }
}

/**
 * DESIGN §6.3–6.8: the card for a message's media; the one-line placeholder for what has none to show. `time` (iOS 1.7):
 * on a dark capsule in the card's corner; a voice note has it under its length, clear of the bars.
 */
@Composable
fun MediaBubble(message: Message, kind: MessageKind, time: String?, placeholder: @Composable () -> Unit) {
    val media = MediaInfo(message.content)
    // Instagram reports no upload progress: a spinner until it answers.
    val uploading = message.isLocalEcho && message.status == Message.Status.Sending
    val voice = media.album.size < 2 && (kind == MessageKind.Voice || kind == MessageKind.Audio) && media.url != null
    if (voice) return VoiceBubble(message, media, time)
    Box {
        MediaCard(message, kind, media, uploading, placeholder)
        time?.let { CardTime(it, Modifier.align(Alignment.BottomEnd), spoken = true) }
    }
}

@Composable
private fun MediaCard(message: Message, kind: MessageKind, media: MediaInfo, uploading: Boolean, placeholder: @Composable () -> Unit) {
    when {
        media.album.size >= 2 -> AlbumPile(message, media)
        kind == MessageKind.Voice || kind == MessageKind.Audio -> placeholder() // no file
        (kind == MessageKind.Image || kind == MessageKind.Gif || kind == MessageKind.Sticker) && media.url != null ->
            PhotoCard(message.id, media, sticker = kind == MessageKind.Sticker, tapHere = true, uploading = uploading)
        kind == MessageKind.Video && media.url != null -> VideoCard(message, media, share = null, tapHere = true, uploading = uploading)
        kind is MessageKind.Share && kind.type == ShareType.POST && media.url != null -> PostCard(message, media)
        kind is MessageKind.Share && (kind.type == ShareType.REEL || kind.type == ShareType.STORY) -> VideoCard(message, media, kind, tapHere = true)
        else -> placeholder() // view once, files, other shares, the mailbox's copies before the thread is read
    }
}

/**
 * iOS 1.7: a card's time (a photo, video, link preview) on a dark capsule in its corner, readable over any picture.
 * `spoken`: TalkBack reads it after the card's own words; a link's bubble says its time itself.
 */
@Composable
fun CardTime(time: String, modifier: Modifier, spoken: Boolean) {
    Text(time, modifier.padding(6.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp)
        .clearAndSetSemantics { if (spoken) contentDescription = time },
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = Color.White)
}

/** Fits `w`×`h` inside `maxW`×`maxH` (dp); 200×200 without a size. */
private fun fit(w: Double?, h: Double?, maxW: Float, maxH: Float): Pair<Dp, Dp> {
    if (w == null || h == null || w <= 0 || h <= 0) return 200.dp to 200.dp
    val scale = minOf(maxW / w, maxH / h)
    return (w * scale).toFloat().dp to (h * scale).toFloat().dp
}

private fun animationsOff(context: android.content.Context) =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** GIFs that have played their three times this launch: they rest on their first frame. */
private val playedGIFs = mutableSetOf<String>()

/**
 * DESIGN §6.4: a photo fitted inside 240×320, a sticker inside 140×140 with no card. A GIF plays three times once per
 * launch, then rests; a sticker loops. Neither plays in a request or with animations off. Tap: full screen.
 */
@Composable
private fun PhotoCard(id: String, media: MediaInfo, sticker: Boolean, tapHere: Boolean, uploading: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ctx = LocalMedia.current
    val url = media.url ?: return
    val (w, h) = if (sticker) 140.dp to 140.dp else fit(media.width, media.height, 240f, 320f)
    var failed by remember(url) { mutableStateOf(false) }
    val progress = MediaFiles.progress[url]
    val open = {
        if (ctx != null) fetch(url, media.mimetype, ctx.room, { failed = true }) { failed = false; ctx.open(Viewer.Photo(it, media.isAnimated)) }
    }
    if (tapHere) OnTap(open)
    val animate = media.isAnimated && Build.VERSION.SDK_INT >= 28 && ctx?.autoplay == true && !animationsOff(context) &&
        (sticker || url !in playedGIFs)
    val px = with(LocalDensity.current) { maxOf(w, h).roundToPx() }
    val still = Pictures.load(if (media.isAnimated) media.thumbnailURL ?: url else url, px)
    Box(
        // A sticker has no card, once it's there: till then the same grey as a photo's.
        modifier.size(w, h).clip(RoundedCornerShape(if (sticker && still != null) 0.dp else 18.dp))
            .background(if (sticker && still != null) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (tapHere) Modifier else Modifier.ownTap(open))
            .semantics { contentDescription = if (sticker) "Sticker" else if (media.isAnimated) "GIF" else "Photo" },
        contentAlignment = Alignment.Center,
    ) {
        still?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = if (sticker) ContentScale.Fit else ContentScale.Crop) }
        if (animate && Build.VERSION.SDK_INT >= 28) {
            Animated(url, media.mimetype, if (sticker) AnimatedImageDrawable.REPEAT_INFINITE else 2, Modifier.fillMaxSize()) { playedGIFs += url }
        } else if (media.isAnimated && !sticker) {
            PlayGlyph()
        }
        Overlay(progress, failed)
        if (uploading) Spinner()
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 3.dp)
    }
}

/** An animated GIF or WebP from its file (Android 9+), looping `loops` more times; `started` once it does. */
@androidx.annotation.RequiresApi(28)
@Composable
private fun Animated(url: String, mimetype: String?, loops: Int, modifier: Modifier, started: () -> Unit = {}) {
    val drawable by produceState<Drawable?>(null, url) {
        value = withContext(Dispatchers.IO) {
            MediaFiles.download(url, mimetype)?.let { runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(it)) }.getOrNull() }
        }
    }
    val d = drawable ?: return
    AndroidView({ ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }, modifier, update = { view ->
        if (view.drawable !== d) {
            view.setImageDrawable(d)
            (d as? AnimatedImageDrawable)?.let {
                it.repeatCount = loops
                it.start()
                started()
            }
        }
    })
}

/** A download's ring, or a failed one's retry glyph. */
@Composable
private fun Overlay(progress: Float?, failed: Boolean) {
    when {
        progress != null -> Box(Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator({ progress }, Modifier.size(30.dp), color = Color.White, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = 0.3f))
        }
        failed -> Box(Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.retry, "Try again", Modifier.size(24.dp), tint = Color.White)
        }
    }
}

@Composable
private fun PlayGlyph() {
    Box(Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.play, null, Modifier.size(26.dp), tint = Color.White)
    }
}

/**
 * DESIGN §6.5: photos and videos sent together, a pile of up to five tilted cards with "3 photos"; a tap lays them
 * out, one card each, until "Show less".
 */
@Composable
private fun AlbumPile(message: Message, media: MediaInfo) {
    var open by rememberSaveable(message.id) { mutableStateOf(false) }
    val items = media.album.map(::MediaInfo)
    if (open) {
        OnTap { }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                if (item.isVideo) VideoCard(message, item, share = null, tapHere = false) else PhotoCard(message.id, item, sticker = false, tapHere = false)
            }
            TextButton({ open = false }) { Text("Show less") }
        }
        return
    }
    OnTap { open = true }
    val (w, h) = fit(items.first().width, items.first().height, 240f, 320f)
    val px = with(LocalDensity.current) { maxOf(w, h).roundToPx() }
    Box(Modifier.padding(8.dp).semantics { contentDescription = "${media.albumLabel}, double tap to show them" }) {
        val angles = listOf(0f, -3f, 3f, -6f, 6f)
        items.take(5).withIndex().reversed().forEach { (i, item) ->
            Box(Modifier.size(w, h).rotate(angles[i]).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Pictures.load(item.previewURL, px)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
        Text(media.albumLabel, Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
        // "Share All" under way: the set's downloads so far.
        val downloading = items.mapNotNull { item -> item.url?.let { MediaFiles.progress[it] } }
        if (downloading.isNotEmpty()) Box(Modifier.align(Alignment.Center)) { Overlay(downloading.average().toFloat(), failed = false) }
    }
}

/**
 * DESIGN §6.6: a video, reel or story on its thumbnail. A reel is 220×330, a story 160×284, a video 220 wide between
 * 9:16 and 16:9. Shares start as "Load video" (a read: the engine fetches the reel or story), then open as if tapped.
 */
@Composable
private fun VideoCard(message: Message, media: MediaInfo, share: MessageKind.Share?, tapHere: Boolean, uploading: Boolean = false) {
    val ctx = LocalMedia.current
    val caption = ShareCaption.of(message.content)
    val unresolved = share?.state == ShareState.UNRESOLVED
    val (w, h) = when (share?.type) {
        ShareType.REEL -> 220.dp to 330.dp
        ShareType.STORY -> 160.dp to 284.dp
        else -> {
            val ratio = ((media.height ?: 16.0) / (media.width ?: 9.0)).coerceIn(9.0 / 16, 16.0 / 9)
            220.dp to (220 * ratio).toFloat().dp
        }
    }
    var resolving by remember(message.id) { mutableStateOf(false) }
    var failed by remember(message.id) { mutableStateOf<String?>(null) }
    var autoOpen by remember(message.id) { mutableStateOf(false) }
    val url = media.url
    val progress = url?.let { MediaFiles.progress[it] }
    fun play(u: String) {
        val room = ctx?.room ?: return
        fetch(u, media.mimetype, room, { failed = "Couldn't get it." }) {
            failed = null
            ctx.open(if (media.isVideo) Viewer.Video(it) else Viewer.Photo(it, animated = false))
        }
    }
    // After "Load video", the share's link arrives as an edit: then it opens as if tapped.
    LaunchedEffect(url, autoOpen) {
        if (autoOpen && url != null && !unresolved) {
            autoOpen = false
            play(url)
        }
    }
    val tap = {
        when {
            resolving -> {}
            unresolved -> {
                val room = ctx?.room
                if (room != null) {
                    resolving = true
                    failed = null
                    AppModel.resolveMedia(message, room) { error ->
                        resolving = false
                        if (error != null) failed = error else autoOpen = true
                    }
                }
            }
            url != null -> play(url)
        }
    }
    if (tapHere) OnTap(tap)
    val px = with(LocalDensity.current) { maxOf(w, h).roundToPx() }
    Box(Modifier.size(w, h).clip(RoundedCornerShape(18.dp)).background(Color.Black).then(if (tapHere) Modifier else Modifier.ownTap(tap))) {
        Pictures.load(media.thumbnailURL, px)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (share != null) {
            val author = caption.author?.let { if (share.type == ShareType.STORY) "Story · @$it" else "@$it" } ?: share.type.label
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.of(share.type.glyph), null, Modifier.size(16.dp), tint = Color.White)
                Text(author, Modifier.padding(start = 6.dp), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        caption.caption?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 30.dp), color = Color.White,
                style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        media.duration?.let {
            // Bottom start: the bubble's time has the bottom end (iOS 1.7).
            Text(clock(it), Modifier.align(Alignment.BottomStart).padding(8.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.align(Alignment.Center)) {
            when {
                resolving -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                    Text("Getting ${share?.type?.label?.lowercase() ?: "video"}…", Modifier.padding(top = 6.dp), color = Color.White,
                        style = MaterialTheme.typography.labelMedium)
                }
                failed != null -> Pill(Icons.retry, "Try again")
                unresolved -> Pill(Icons.play, if (share?.type == ShareType.STORY) "Load story" else "Load video")
                uploading -> Spinner()
                progress != null -> Overlay(progress, failed = false)
                else -> PlayGlyph()
            }
        }
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(Modifier.background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
        Text(label, Modifier.padding(start = 6.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** DESIGN §6.7: a shared post, 240 wide: who posted it, its picture (between 16:9 and 4:5) and two lines of caption. */
@Composable
private fun PostCard(message: Message, media: MediaInfo) {
    val ctx = LocalMedia.current
    val caption = ShareCaption.of(message.content)
    val url = media.url ?: return
    var failed by remember(url) { mutableStateOf(false) }
    OnTap { if (ctx != null) fetch(url, media.mimetype, ctx.room, { failed = true }) { failed = false; ctx.open(Viewer.Photo(it, animated = false)) } }
    val ratio = ((media.height ?: 1.0) / (media.width ?: 1.0)).coerceIn(0.5625, 1.25)
    val px = with(LocalDensity.current) { 240.dp.roundToPx() }
    Column(Modifier.width(240.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.of(fi.ferry.messenger.model.Glyph.POST), null, Modifier.size(16.dp))
            Text(caption.author?.let { "@$it" } ?: "Post", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height((240 * ratio).toFloat().dp).background(Color.Black), contentAlignment = Alignment.Center) {
            Pictures.load(url, px)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Overlay(MediaFiles.progress[url], failed)
        }
        caption.caption?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * DESIGN §6.8: a voice note, 240 wide: play, bars (made up from the message's ID: Instagram sends no waveform), the time,
 * and while it plays the speed. Drag the bars to seek.
 */
@Composable
private fun VoiceBubble(message: Message, media: MediaInfo, time: String?) {
    val context = LocalContext.current
    val ctx = LocalMedia.current
    val own = message.isOwn
    val identity = ctx?.room?.let { identityOf(it) }
    val fill = if (own) identity?.color?.fillColor ?: MaterialTheme.colorScheme.onBackground else Bubbles.incoming
    val ink = if (own) Color.White else MaterialTheme.colorScheme.onBackground
    val url = media.url ?: return
    val duration = media.duration ?: 0L
    val loaded = VoicePlayer.current == message.id
    val playing = loaded && VoicePlayer.playing
    var position by remember { mutableLongStateOf(0L) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(loaded, playing) {
        position = if (loaded) VoicePlayer.position else 0
        while (playing) {
            position = VoicePlayer.position
            delay(100)
        }
    }
    val toggle = {
        if (ctx != null) fetch(url, media.mimetype, ctx.room, { failed = true }) { failed = false; VoicePlayer.toggle(context, message.id, it) }
    }
    OnTap(toggle)
    val heights = remember(message.id) { voiceBars(message.id, BARS) }
    Row(
        Modifier.width(240.dp).heightIn(min = 48.dp).background(fill, RoundedCornerShape(24.dp)).padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = "Voice message, ${clock(duration)}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(32.dp).background(ink.copy(alpha = 0.18f), CircleShape).clickable(onClick = toggle), contentAlignment = Alignment.Center) {
            when {
                MediaFiles.progress[url] != null -> CircularProgressIndicator(Modifier.size(18.dp), color = ink, strokeWidth = 2.dp)
                failed -> Icon(Icons.retry, "Try again", Modifier.size(18.dp), tint = ink)
                else -> Icon(if (playing) Icons.pause else Icons.play, if (playing) "Pause" else "Play", Modifier.size(20.dp), tint = ink)
            }
        }
        val done = if (duration > 0) position.toFloat() / duration else 0f
        Canvas(
            Modifier.weight(1f).height(28.dp).pointerInput(message.id, duration, loaded) {
                if (!loaded || duration <= 0) return@pointerInput
                detectHorizontalDragGestures { change, _ -> VoicePlayer.seek(message.id, (change.position.x / size.width * duration).toLong().coerceIn(0, duration)) }
            },
        ) {
            val bar = 3.dp.toPx()
            val gap = (size.width - bar * BARS) / (BARS - 1)
            heights.forEachIndexed { i, fraction ->
                val x = i * (bar + gap)
                val barHeight = size.height * fraction
                drawRoundRect(if (i.toFloat() / BARS < done) ink else ink.copy(alpha = 0.4f), Offset(x, (size.height - barHeight) / 2),
                    Size(bar, barHeight), CornerRadius(bar / 2))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(clock(if (loaded) position else duration), color = ink, style = MaterialTheme.typography.labelMedium)
            time?.let { Text(it, Modifier.clearAndSetSemantics { contentDescription = it }, color = ink.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")) }
        }
        if (loaded) {
            Text("${VoicePlayer.speed.toString().removeSuffix(".0")}×", Modifier.clip(CircleShape).clickable { VoicePlayer.cycleSpeed() }
                .background(ink.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp), color = ink, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private const val BARS = 26

/** Bar heights from 0.25 to 1, the same for a message every time (FNV-1a of its ID, then a linear congruential walk). */
fun voiceBars(id: String, count: Int): List<Float> {
    var seed = id.fold(0x811c9dc5.toInt()) { h, c -> (h xor c.code) * 0x01000193 }
    return List(count) {
        seed = seed * 1664525 + 1013904223
        0.25f + 0.75f * ((seed ushr 8) and 0xFFFF) / 65535f
    }
}

/** "0:07", "12:30". */
fun clock(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

// Full screen

/** A photo (pinch to 5×, double tap for 2.5×) or a video (looping; tap to pause, the sound, a scrubber). Back closes. */
@Composable
fun MediaViewer(viewer: Viewer, dismiss: () -> Unit) {
    Dialog(dismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (viewer) {
                is Viewer.Photo -> PhotoView(viewer)
                is Viewer.Video -> VideoView(viewer.file)
            }
            // On a dark circle, as iOS 1.3: white alone vanishes over a bright photo.
            val round = Modifier.statusBarsPadding().padding(12.dp).size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
            IconButton(dismiss, Modifier.align(Alignment.TopStart).then(round)) {
                Icon(Icons.close, "Close", tint = Color.White)
            }
            // As iOS: a photo has Share, a video none.
            if (viewer is Viewer.Photo) {
                val context = LocalContext.current
                IconButton({ share(context, listOf(viewer.file)) }, Modifier.align(Alignment.TopEnd).then(round)) {
                    Icon(Icons.share, "Share", tint = Color.White)
                }
            }
        }
    }
}

/** Android's share sheet for media files of ours (the cache's, through `FileProvider`): one, or a set ("Share All"). */
fun share(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
    val types = files.map { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.extension) ?: "application/octet-stream" }
    // One type, else "image/*" for photos of several types, else anything.
    val type = types.distinct().singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    send.type = type
    // Read access for whichever app is picked, and the sheet's previews.
    send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

// ponytail: no swipe to dismiss yet; Back and ✕ close it.
@Composable
private fun PhotoView(viewer: Viewer.Photo) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoom = Modifier.fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures(onDoubleTap = {
                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
            })
        }
        .pointerInput(Unit) {
            detectTransformGestures { _, pan, factor, _ ->
                scale = (scale * factor).coerceIn(1f, 5f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
            }
        }
        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
    if (viewer.animated && Build.VERSION.SDK_INT >= 28) {
        val drawable by produceState<Drawable?>(null, viewer.file) {
            value = withContext(Dispatchers.IO) { runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(viewer.file)) }.getOrNull() }
        }
        drawable?.let { d ->
            AndroidView({ ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } }, zoom, update = { view ->
                if (view.drawable !== d) {
                    view.setImageDrawable(d)
                    (d as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
                }
            })
        }
        return
    }
    val px = with(LocalDensity.current) { 2048.dp.roundToPx().coerceAtMost(4096) }
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, viewer.file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(viewer.file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= px / 2 && bounds.outHeight / (sample * 2) >= px / 2) sample *= 2
                BitmapFactory.decodeFile(viewer.file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    image?.let { Image(it, "Photo", zoom, contentScale = ContentScale.Fit) }
}

@Composable
private fun VideoView(file: File) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true).build().apply {
            setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    var playing by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    DisposableEffect(player) {
        VoicePlayer.stop()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(200)
        }
    }
    Box(Modifier.fillMaxSize().pointerInput(player) { detectTapGestures { if (player.isPlaying) player.pause() else player.play() } }) {
        ContentFrame(player, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (!playing) Box(Modifier.align(Alignment.Center)) { PlayGlyph() }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
            .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton({ muted = !muted; player.volume = if (muted) 0f else 1f }) {
                Icon(if (muted) Icons.muted else Icons.sound, if (muted) "Sound on" else "Mute", tint = Color.White)
            }
            Slider(
                if (duration > 0) position.toFloat() / duration else 0f,
                { player.seekTo((it * duration).toLong()); position = (it * duration).toLong() },
                Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
            )
            Text("${clock(position)} / ${clock(duration)}", Modifier.padding(start = 8.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}
