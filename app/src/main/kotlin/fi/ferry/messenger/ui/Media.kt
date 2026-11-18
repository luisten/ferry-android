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
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import android.widget.ImageView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.MediaFiles
import fi.ferry.messenger.VoicePlayer
import fi.ferry.messenger.model.AccountIdentity
import fi.ferry.messenger.model.MediaInfo
import fi.ferry.messenger.model.Message
import fi.ferry.messenger.model.MessageKind
import fi.ferry.messenger.model.MessageKind.ShareState
import fi.ferry.messenger.model.MessageKind.ShareType
import fi.ferry.messenger.model.Room
import fi.ferry.messenger.model.ShareCaption
import fi.ferry.messenger.model.Timeline.GroupPosition
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What a full-screen viewer shows: a photo (a GIF plays there), or a video (iOS's `MediaViewerItem`). */
sealed interface Viewer {
    data class Photo(val file: File, val animated: Boolean) : Viewer
    /** `sender`: who shared it, for "Shared by Kivo · 9:31 AM" (DESIGN M1). */
    data class Video(val file: File, val message: Message, val sender: String) : Viewer
}

/**
 * What the thread lends its media cards: its room (for links and "Load video"), whether GIFs may play, a sender's
 * first name ("You" for us) for the player, the viewer.
 */
class MediaContext(val room: Room, val autoplay: Boolean, val firstName: (String) -> String, val open: (Viewer) -> Unit)

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
 * on a dark capsule in a picture's corner, and beside a card's own words where they reach the bottom (a placeholder's
 * label, a post's caption, a pile's count, a voice note's length), never over them (A35).
 */
@Composable
fun MediaBubble(message: Message, kind: MessageKind, time: String?, shape: Shape, placeholder: @Composable (time: String?) -> Unit) {
    val media = MediaInfo(message.content)
    // Instagram reports no upload progress: a spinner until it answers.
    val uploading = message.isLocalEcho && message.status == Message.Status.Sending
    when {
        media.album.size >= 2 -> AlbumPile(message, media, time)
        kind == MessageKind.Voice || kind == MessageKind.Audio -> if (media.url != null) VoiceBubble(message, media, time, shape) else placeholder(time)
        (kind == MessageKind.Image || kind == MessageKind.Gif || kind == MessageKind.Sticker) && media.url != null ->
            Timed(time) { PhotoCard(message.id, media, sticker = kind == MessageKind.Sticker, tapHere = true, shape = shape, uploading = uploading) }
        kind == MessageKind.Video && media.url != null -> Timed(time) { VideoCard(message, media, share = null, tapHere = true, shape = shape, uploading = uploading) }
        kind is MessageKind.Share && kind.type == ShareType.POST && media.url != null -> PostCard(message, media, time, shape)
        kind is MessageKind.Share && (kind.type == ShareType.REEL || kind.type == ShareType.STORY) -> Timed(time) { VideoCard(message, media, kind, tapHere = true, shape = shape) }
        else -> placeholder(time) // view once, files, other shares, the mailbox's copies before the thread is read
    }
}

/** A picture with the time in its bottom end corner. */
@Composable
private fun Timed(time: String?, card: @Composable () -> Unit) {
    Box {
        card()
        time?.let { CardTime(it, Modifier.align(Alignment.BottomEnd), spoken = true) }
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

/** Android's "Remove animations" (iOS's Reduce Motion). */
internal fun animationsOff(context: android.content.Context) =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** GIFs that have played their three times this launch: they rest on their first frame. */
private val playedGIFs = mutableSetOf<String>()

/**
 * DESIGN §6.4: a photo fitted inside 240×320, a sticker inside 140×140 with no card. A GIF plays three times once per
 * launch, then rests; a sticker loops. Neither plays in a request or with animations off. Tap: full screen.
 */
@Composable
private fun PhotoCard(id: String, media: MediaInfo, sticker: Boolean, tapHere: Boolean, shape: Shape, uploading: Boolean = false, modifier: Modifier = Modifier) {
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
        modifier.size(w, h).clip(if (sticker && still != null) RectangleShape else shape)
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
    // iOS's video card, GIF play button included: 56 on black at 65%.
    Box(Modifier.size(56.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.play, null, Modifier.size(30.dp), tint = Color.White)
    }
}

/**
 * DESIGN §6.5: photos and videos sent together, a pile of up to five tilted cards with "3 photos"; a tap lays them
 * out, one card each, until "Show less".
 */
@Composable
private fun AlbumPile(message: Message, media: MediaInfo, time: String?) {
    var open by rememberSaveable(message.id) { mutableStateOf(false) }
    val items = media.album.map(::MediaInfo)
    if (open) {
        OnTap { }
        // Stacked like a run of bubbles (iOS): first, middle and last corners, 2 dp apart.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { i, item ->
                val shape = bubbleShape(if (i == 0) GroupPosition.FIRST else if (i == items.lastIndex) GroupPosition.LAST else GroupPosition.MIDDLE, message.isOwn)
                Timed(time.takeIf { i == items.lastIndex }) {
                    if (item.isVideo) VideoCard(message, item, share = null, tapHere = false, shape = shape) else PhotoCard(message.id, item, sticker = false, tapHere = false, shape = shape)
                }
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
            Box(Modifier.size(w, h).rotate(angles[i]).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Pictures.load(item.previewURL, px)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
        }
        // The count and the time share the bottom edge, side by side.
        Box(Modifier.matchParentSize()) {
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(media.albumLabel, Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                time?.let { CardTime(it, Modifier.padding(end = 2.dp), spoken = true) }
            }
        }
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
private fun VideoCard(message: Message, media: MediaInfo, share: MessageKind.Share?, tapHere: Boolean, shape: Shape, uploading: Boolean = false) {
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
            ctx.open(if (media.isVideo) Viewer.Video(it, message, ctx.firstName(message.sender)) else Viewer.Photo(it, animated = false))
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
    Box(Modifier.size(w, h).clip(shape).background(Color(0xFF292929)).then(if (tapHere) Modifier else Modifier.ownTap(tap))) {
        Pictures.load(media.thumbnailURL, px)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        if (share != null) {
            // iOS: "@author" ("Story · @author"), a reel's badge at the end, on a fade from the top.
            val author = caption.author?.let { if (share.type == ShareType.STORY) "Story · @$it" else "@$it" } ?: "Story".takeIf { share.type == ShareType.STORY }
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
                .padding(start = 9.dp, end = 9.dp, top = 9.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(author.orEmpty(), Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (share.type == ShareType.REEL) Box(Modifier.size(26.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.reels, null, Modifier.size(15.dp), tint = Color.White)
                }
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
                unresolved -> Pill(Icons.download, if (share.type == ShareType.STORY) "Load story" else "Load video")
                uploading -> Spinner()
                progress != null -> Overlay(progress, failed = false)
                else -> PlayGlyph()
            }
        }
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(Modifier.heightIn(min = 36.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
        Text(label, Modifier.padding(start = 6.dp), color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** DESIGN §6.7: a shared post, 240 wide: who posted it, its picture (between 16:9 and 4:5) and two lines of caption. */
@Composable
private fun PostCard(message: Message, media: MediaInfo, time: String?, shape: Shape) {
    val ctx = LocalMedia.current
    val caption = ShareCaption.of(message.content)
    val url = media.url ?: return
    var failed by remember(url) { mutableStateOf(false) }
    OnTap { if (ctx != null) fetch(url, media.mimetype, ctx.room, { failed = true }) { failed = false; ctx.open(Viewer.Photo(it, animated = false)) } }
    val ratio = ((media.height ?: 1.0) / (media.width ?: 1.0)).coerceIn(0.5625, 1.25)
    val px = with(LocalDensity.current) { 240.dp.roundToPx() }
    // iOS: the bubble's colour (the account's on ours) and its text colour; the author, then the post's glyph at the end.
    val (fill, ink) = cardColors(message)
    Column(Modifier.width(240.dp).clip(shape).background(fill)) {
        Row(Modifier.heightIn(min = 36.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(caption.author?.let { "@$it" } ?: "Post", Modifier.weight(1f), color = ink, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.of(fi.ferry.messenger.model.Glyph.POST), null, Modifier.padding(start = 8.dp).size(16.dp), tint = ink.copy(alpha = 0.7f))
        }
        Box(Modifier.fillMaxWidth().height((240 * ratio).toFloat().dp).background(Color.Black), contentAlignment = Alignment.Center) {
            Pictures.load(url, px)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Overlay(MediaFiles.progress[url], failed)
            if (caption.caption.isNullOrBlank()) time?.let { CardTime(it, Modifier.align(Alignment.BottomEnd), spoken = true) }
        }
        caption.caption?.takeIf { it.isNotBlank() }?.let {
            // The time beside the caption, not over its end.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(it, Modifier.weight(1f).padding(12.dp), color = ink, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                time?.let { t -> CardTime(t, Modifier, spoken = true) }
            }
        }
    }
}

/**
 * DESIGN §6.8: a voice note, 240 wide: play, bars (made up from the message's ID: Instagram sends no waveform), the time,
 * and while it plays the speed. Drag the bars to seek.
 */
@Composable
private fun VoiceBubble(message: Message, media: MediaInfo, time: String?, shape: Shape) {
    val context = LocalContext.current
    val ctx = LocalMedia.current
    val own = message.isOwn
    val (fill, ink) = cardColors(message)
    // iOS's play disc is solid: white with the account's colour on ours, the text colour with the page's on theirs.
    val disc = if (own) Color.White else ink
    val glyph = if (own) fill else MaterialTheme.colorScheme.background
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
        Modifier.width(240.dp).heightIn(min = 48.dp).background(fill, shape).padding(horizontal = 8.dp, vertical = 8.dp)
            .semantics { contentDescription = "Voice message, ${clock(duration)}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(32.dp).background(disc, CircleShape).clickable(onClick = toggle), contentAlignment = Alignment.Center) {
            when {
                MediaFiles.progress[url] != null -> CircularProgressIndicator(Modifier.size(18.dp), color = glyph, strokeWidth = 2.dp)
                failed -> Icon(Icons.retry, "Try again", Modifier.size(18.dp), tint = glyph)
                else -> Icon(if (playing) Icons.pause else Icons.play, if (playing) "Pause" else "Play", Modifier.size(20.dp), tint = glyph)
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

/** A card's bubble colour and the text colour on it: the account's fill and white on ours, the grey bubble and the page's text on theirs. */
@Composable
private fun cardColors(message: Message): Pair<Color, Color> {
    val identity = LocalMedia.current?.room?.let { identityOf(it) }
    return if (message.isOwn) (identity?.color?.fillColor ?: MaterialTheme.colorScheme.onBackground) to Color.White
    else Bubbles.incoming to MaterialTheme.colorScheme.onBackground
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

/**
 * A photo (pinch to 5×, double tap for 2.5×) or a video (iOS's player: `VideoView`). Back closes; `identity` is the
 * chat's account, for the player's tag.
 */
@Composable
fun MediaViewer(viewer: Viewer, identity: AccountIdentity?, dismiss: () -> Unit) {
    Dialog(dismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        when (viewer) {
            is Viewer.Video -> VideoView(viewer, identity, dismiss)
            is Viewer.Photo -> Box(Modifier.fillMaxSize().background(Color.Black)) {
                PhotoView(viewer)
                // On a dark circle, as iOS 1.3: white alone vanishes over a bright photo.
                val round = Modifier.statusBarsPadding().padding(12.dp).size(44.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)
                IconButton(dismiss, Modifier.align(Alignment.TopStart).then(round)) {
                    Icon(Icons.close, "Close", tint = Color.White)
                }
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


/**
 * DESIGN M1 as iOS's `PlayerView`: one video, looping, aspect-fit on black. Close and mute on top under a grabber
 * (swipe down or up anywhere goes back to the chat; the scrubber's own drag wins), a tap pauses. At the bottom, on a
 * fade: a share's "@author" and Open in Instagram, its caption (two lines, a tap shows the rest), "Shared by Kivo ·
 * 9:31 AM" with the account's tag, and the scrubber once the video says how long it is. The sound starts on and isn't
 * remembered, as on iOS; mute is the player's volume, so the phone's own volume and its keys are left alone.
 */
@Composable
private fun VideoView(viewer: Viewer.Video, identity: AccountIdentity?, dismiss: () -> Unit) {
    val context = LocalContext.current
    val player = remember(viewer.file) {
        ExoPlayer.Builder(context).setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true).build().apply {
            setMediaItem(MediaItem.fromUri(viewer.file.toURI().toString()))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }
    var playing by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    DisposableEffect(player) {
        VoicePlayer.stop()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                failed = true
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
            delay(100)
        }
    }
    // As iOS: no status bar over a video. The navigation bar stays, for Back.
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose {}
    }
    // Swipe down or up past 120 dp to close (a haptic there); short of it, back into place.
    val density = LocalDensity.current
    val limit = with(density) { 120.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val offset by animateFloatAsState(drag, if (dragging) snap() else spring(), label = "drag")
    val haptics = LocalHapticFeedback.current
    val past = abs(drag) > limit
    LaunchedEffect(past) { if (past) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
    val fade = (abs(offset) / with(density) { 500.dp.toPx() }).coerceAtMost(0.6f)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1 - fade)).pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { dragging = true },
                onDragEnd = { dragging = false; if (abs(drag) > limit) dismiss() else drag = 0f },
                onDragCancel = { dragging = false; drag = 0f },
            ) { change, dy -> change.consume(); drag += dy }
        },
    ) {
        Box(Modifier.fillMaxSize().offset { IntOffset(0, offset.roundToInt()) }) {
            val toggle = { if (player.isPlaying) player.pause() else player.play() }
            // Around the frame, not on it: its surface view took the label.
            Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null, role = Role.Button, onClick = toggle)
                .semantics { contentDescription = if (playing) "Pause" else "Play" }) {
                ContentFrame(player, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (failed) {
                PlayerFailure(MediaInfo(viewer.message.content).externalURL, Modifier.align(Alignment.Center)) {
                    failed = false
                    player.prepare()
                    player.play()
                }
            } else if (!playing) {
                Box(Modifier.align(Alignment.Center).size(64.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.play, null, Modifier.size(32.dp), tint = Color.White)
                }
            }
            PlayerOverlay(viewer, identity, failed, muted, position, duration, close = dismiss,
                mute = { muted = !muted; player.volume = if (muted) 0f else 1f }) { player.seekTo(it); position = it }
        }
    }
}

/** iOS's `mediaGlass` without the glass: black at 65%. */
private val mediaGlass = Color.Black.copy(alpha = 0.65f)

/** The player's controls and its context (iOS's `PlayerView.overlay`), white on the video. */
@Composable
private fun PlayerOverlay(
    viewer: Viewer.Video, identity: AccountIdentity?, failed: Boolean, muted: Boolean, position: Long, duration: Long,
    close: () -> Unit, mute: () -> Unit, seek: (Long) -> Unit,
) {
    val context = LocalContext.current
    val info = MediaInfo(viewer.message.content)
    val caption = ShareCaption.of(viewer.message.content)
    val link = info.externalURL
    val text = if (link == null) "" else listOfNotNull(if (caption.author == null) caption.title else null, caption.subtitle, caption.caption).joinToString("\n")
    var expanded by remember { mutableStateOf(false) }
    val subheadline = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp)
    val footnote = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp)
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 12.dp, end = 12.dp, top = 6.dp)) {
            Box(Modifier.align(Alignment.TopCenter).size(36.dp, 5.dp).background(Color.White.copy(alpha = 0.55f), CircleShape))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(close, Modifier.size(48.dp)) {
                    Box(Modifier.size(44.dp).background(mediaGlass, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.close, "Close", tint = Color.White)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(mute, Modifier.size(48.dp)) {
                    Box(Modifier.size(44.dp).background(mediaGlass, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(if (muted) Icons.muted else Icons.sound, if (muted) "Unmute" else "Mute", Modifier.size(22.dp), tint = Color.White)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 16.dp, top = 60.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (link != null && !failed) { // a failure offers its own way out, in the middle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(caption.author?.let { "@$it" }.orEmpty(), Modifier.weight(1f), color = Color.White, style = subheadline,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(start = 10.dp).clip(CircleShape).background(mediaGlass).clickable { openInstagram(context, link) }
                        .heightIn(min = 30.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Open in Instagram", color = Color.White, style = footnote, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.openOutside, null, Modifier.padding(start = 4.dp).size(14.dp), tint = Color.White)
                    }
                }
            }
            if (text.isNotEmpty() && !failed) {
                // The caption gives way when the rest needs the room (landscape at a large font size): measured last.
                val toggle = Modifier.weight(1f, fill = false).fillMaxWidth().clickable(interactionSource = null, indication = null) { expanded = !expanded }
                if (expanded) {
                    Text(text, toggle.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), color = Color.White, style = subheadline)
                } else {
                    Text(text, toggle, color = Color.White, style = subheadline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // One line for TalkBack: "Shared by Kivo · 9:31 AM, Studio account".
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Shared by ${viewer.sender} · ${sharedTime(context, viewer.message.ts)}", Modifier.weight(1f, fill = false),
                    color = Color.White.copy(alpha = 0.75f), style = footnote)
                identity?.let { AccountTag(it) }
            }
            // Nothing to scrub until the video says how long it is (none on a failure).
            if (duration > 0) Scrubber(position, duration, seek)
        }
    }
}

/** iOS's `timeText`: "9:31 AM" today, else "Oct 12, 9:31 AM", in the phone's 12 or 24 hours. */
private fun sharedTime(context: Context, ts: Long): String =
    DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_TIME or
        (if (DateUtils.isToday(ts)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH))

private fun openInstagram(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
}

/**
 * Elapsed and remaining under a 3 dp track that thickens to 8 while dragged (DESIGN M1); a touch seeks at once. TalkBack:
 * "Position, 0:12 of 0:42", 5 s a step.
 */
@Composable
private fun Scrubber(position: Long, duration: Long, seek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.let { (it * duration).toLong() } ?: position
    val fraction = (shown.toFloat() / duration).coerceIn(0f, 1f)
    val thickness by animateDpAsState(if (dragging == null) 3.dp else 8.dp, tween(150), label = "track")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val seconds = duration / 1000f
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = "Position"
            stateDescription = "${clock(position)} of ${clock(duration)}"
            progressBarRangeInfo = ProgressBarRangeInfo(position / 1000f, 0f..seconds, steps = ((seconds / 5).toInt() - 1).coerceAtLeast(0))
            setProgress { seek((it.coerceIn(0f, seconds) * 1000).toLong()); true }
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Canvas(
            Modifier.fillMaxWidth().height(24.dp).pointerInput(duration, rtl) {
                awaitEachGesture {
                    fun at(x: Float) {
                        val f = (x / size.width).coerceIn(0f, 1f).let { if (rtl) 1 - it else it }
                        dragging = f
                        seek((f * duration).toLong())
                    }
                    val down = awaitFirstDown()
                    down.consume()
                    at(down.position.x)
                    drag(down.id) { at(it.position.x); it.consume() }
                    dragging = null
                }
            },
        ) {
            val h = thickness.toPx()
            val y = (size.height - h) / 2
            val filled = size.width * fraction
            drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(Color.White, Offset(if (rtl) size.width - filled else 0f, y), Size(filled, h), CornerRadius(h / 2))
        }
        Row {
            val style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
            Text(clock(shown), color = Color.White.copy(alpha = 0.75f), style = style)
            Spacer(Modifier.weight(1f))
            // Behind an LTR mark, so a right-to-left language doesn't put the minus after the time.
            Text("\u200E−" + clock(duration - shown), color = Color.White.copy(alpha = 0.75f), style = style)
        }
    }
}

/**
 * DESIGN M1's failed state: what happened, then Try Again and, for a share, the way out to Instagram. The file is
 * already here, so the cause is playback on this phone, not the share.
 */
@Composable
private fun PlayerFailure(link: String?, modifier: Modifier, retry: () -> Unit) {
    val context = LocalContext.current
    Column(modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Couldn't play this video", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp), fontWeight = FontWeight.SemiBold)
        Text("It downloaded, but this phone couldn't play it.", color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), textAlign = TextAlign.Center)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCapsule(Icons.retry, "Try Again", retry)
            link?.let { GlassCapsule(Icons.openOutside, "Open in Instagram") { openInstagram(context, it) } }
        }
    }
}

@Composable
private fun GlassCapsule(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(mediaGlass).clickable(onClick = action).heightIn(min = 44.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
        Text(label, Modifier.padding(start = 6.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            fontWeight = FontWeight.SemiBold)
    }
}
