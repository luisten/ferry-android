package fi.ferry.messenger.ui

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.os.PowerManager
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.ferry.messenger.model.AccountColor
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * O1 (DESIGN O1, iOS `WelcomeView`): the wordmark and headline, the icon's ferry crossing a calm sea with two accounts
 * aboard, the three promises, and the one button. Everything you read or tap is on the plain background; only the
 * picture uses the brand's sea. Android shows it only with no account, so the ferry carries iOS's two samples.
 */
@Composable
fun Welcome(login: () -> Unit) {
    val density = LocalDensity.current
    // iOS's accessibility text sizes: the picture shrinks to 70% and the legal line moves into the scroll (decision A57).
    val large = density.fontScale >= 1.5f
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(login, Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                    Text("Connect Instagram account", Modifier.padding(vertical = if (large) 8.dp else 0.dp),
                        style = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
                }
                if (!large) Legal()
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            // At least the visible height, so spare room splits above and below the content (iOS's two spacers).
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Header(large)
                CrossingScene(if (large) 0.7f else 1f, Modifier.padding(top = 16.dp))
                Promises(large, Modifier.padding(top = 28.dp))
                if (large) Legal(Modifier.padding(top = 28.dp, start = 24.dp, end = 24.dp))
            }
        }
    }
}

/** One heading for TalkBack: "Ferry. All your Instagram DMs. None of the feed." */
@Composable
private fun Header(large: Boolean) {
    // 48 sp, growing with the text size up to 62 dp (iOS stops it at AX1).
    val wordmark = with(LocalDensity.current) { if (48.sp.toDp() > 62.dp) 62.dp.toSp() else 48.sp }
    Column(
        Modifier.padding(horizontal = 24.dp).clearAndSetSemantics {
            contentDescription = "Ferry. All your Instagram DMs. None of the feed."
            heading()
        },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Ferry", style = TextStyle(fontSize = wordmark, lineHeight = wordmark * 1.2f, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp))
        Text("All your Instagram DMs.\nNone of the feed.", Modifier.widthIn(max = if (large) 345.dp else 320.dp),
            style = TextStyle(fontSize = 20.sp, lineHeight = 25.sp), textAlign = TextAlign.Center)
    }
}

/** A centred column whose rows start together; as wide as its rows while they fit on one line, else the screen's. */
@Composable
private fun Promises(large: Boolean, modifier: Modifier) {
    val disc = with(LocalDensity.current) { minOf(36.sp.toDp(), 50.dp) }
    Column(modifier.padding(horizontal = 24.dp).width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(if (large) 20.dp else 16.dp)) {
        Promise(Icons.tray, "Every account, one inbox", disc, large)
        Promise(Icons.reels, "Reels play right in the chat", disc, large)
        Promise(Icons.noFeed, "No feed, no Explore, no algorithm", disc, large)
    }
}

@Composable
private fun Promise(icon: ImageVector, text: String, disc: Dp, large: Boolean) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = if (large) Alignment.Top else Alignment.CenterVertically) {
        Box(Modifier.size(disc).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(disc * 0.56f), tint = MaterialTheme.colorScheme.onBackground)
        }
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Legal(modifier: Modifier = Modifier) {
    Text("Not affiliated with Instagram or Meta.", modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

/**
 * O1's picture (iOS `CrossingScene`), back to front: the sky is the screen's background; a haze above the horizon; the
 * sea band with a wavy lower edge; two swell lines; a short wake; the icon's white hull with two accounts' bubbles
 * aboard. One image to TalkBack. `scale` is 1, or 0.7 at large text sizes.
 */
@Composable
fun CrossingScene(scale: Float, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val sea = SeaColors(Scenery.seaHorizon, Scenery.seaFloor, Scenery.seaHaze, Scenery.foam, if (dark) 0.4f else 0.5f)
    val motion = rememberMotion()
    val clock = rememberCrossingClock(motion)
    fun time() = if (motion == Crossing.Motion.STILL) null else clock.floatValue
    // Changes only while the bubbles board, so afterwards the ferry's drawing is recorded once.
    val boarding by remember(motion) { derivedStateOf { Crossing.boarding(time()) } }
    Box(
        modifier.fillMaxWidth().height(Crossing.HEIGHT.dp * scale).clearAndSetSemantics {
            contentDescription = "A ferry carrying messages across calm water."
            role = Role.Image
        },
    ) {
        // Only drawing reads the clock: a frame redraws the sea and recomposes nothing. Everything inside in dp.
        Canvas(Modifier.matchParentSize()) {
            scale(density, Offset.Zero) { drawSea(size.width / density, scale, Crossing.pose(time()), sea) }
        }
        // The ferry in its own offscreen layer (iOS's overlay): its glide, bob and roll move the layer, not the drawing.
        Canvas(
            Modifier.matchParentSize().graphicsLayer {
                val t = time()
                val pose = Crossing.pose(t)
                val ferry = Crossing.boarding(t).ferry
                translationX = -28.dp.toPx() * (1 - ferry)
                translationY = pose.bob.dp.toPx()
                rotationZ = pose.roll
                alpha = ferry.coerceIn(0f, 1f)
                // Around the keel's centre: the artboard's (512, 700).
                transformOrigin = TransformOrigin(0.5f, Crossing.keel(scale) / (Crossing.HEIGHT * scale))
                compositingStrategy = CompositingStrategy.Offscreen
            },
        ) {
            scale(density, Offset.Zero) { drawFerry(size.width / density, scale, boarding) }
        }
    }
}

private class SeaColors(val horizon: Color, val floor: Color, val haze: Color, val foam: Color, val swellOpacity: Float)

private fun DrawScope.drawSea(width: Float, k: Float, pose: Crossing.Pose, colors: SeaColors) {
    val horizon = Crossing.HORIZON * k
    val edge = Crossing.EDGE * k
    drawRect(Brush.verticalGradient(listOf(colors.haze.copy(alpha = 0f), colors.haze), horizon - 40 * k, horizon),
        Offset(0f, horizon - 40 * k), Size(width, 40 * k))

    // The band runs well past both sides so its drifting edge always covers the width.
    val half = Crossing.EDGE_WAVELENGTH / 2
    val count = ceil((width + 2 * Crossing.EDGE_WAVELENGTH) / half).toInt() + 1
    val left = -Crossing.EDGE_WAVELENGTH - pose.edge
    val right = left + count * half
    val band = Path().apply {
        moveTo(left, horizon)
        lineTo(right, horizon)
        lineTo(right, edge)
        arches(right, edge, -half, 5 * k, count)
        close()
    }
    drawPath(band, Brush.verticalGradient(listOf(colors.horizon, colors.floor), horizon, edge))

    for ((i, line) in listOf(54f to 1f, 70f to 0.7f).withIndex()) {
        val y = horizon + line.first * k
        val wavelength = Crossing.SWELL_WAVELENGTHS[i]
        val n = ceil((width + 2 * wavelength) / (wavelength / 2)).toInt() + 1
        val start = -wavelength - pose.swell[i]
        val swell = Path().apply {
            moveTo(start, y)
            arches(start, y, wavelength / 2, 2.5f * k, n)
        }
        drawPath(swell, colors.foam, alpha = colors.swellOpacity * line.second, style = Stroke(1.5f, cap = StrokeCap.Round))
    }

    // The wake: two short strokes from behind the stern, at the waterline; the hull covers their start.
    val s = Crossing.FERRY_SCALE * k
    val stern = width / 2 - 512 * s + 175 * s
    val water = horizon + 26 * k
    val near = Path().apply {
        moveTo(stern + 6 * k, water)
        cubicTo(stern - 10 * k, water + 4 * k, stern - 26 * k, water + 4 * k, stern - 40 * k, water + 2 * k)
    }
    val far = Path().apply {
        moveTo(stern + 12 * k, water + 10 * k)
        cubicTo(stern - 2 * k, water + 13 * k, stern - 14 * k, water + 14 * k, stern - 26 * k, water + 13 * k)
    }
    drawPath(near, colors.foam, alpha = pose.wake[0], style = Stroke(2f, cap = StrokeCap.Round))
    drawPath(far, colors.foam, alpha = pose.wake[1], style = Stroke(2f, cap = StrokeCap.Round))
}

/**
 * The icon's artboard at 0.3 (hull 210 dp wide), centred, its deck 6 dp below the horizon. It glides in from the left,
 * then the bubbles board; afterwards it bobs and rolls around the keel.
 */
private fun DrawScope.drawFerry(width: Float, k: Float, boarding: Crossing.Boarding) {
    val s = Crossing.FERRY_SCALE * k
    translate(width / 2 - 512 * s, Crossing.keel(k) - 700 * s) {
        scale(s, Offset.Zero) {
            drawPath(FerryShape.hull, Color.White)
            val (front, back) = Crossing.samples
            bubble(FerryShape.backBubble, back, Offset(686f, 306f), 96f, Offset(692.8f, 436.8f), boarding.back, 12 * k / s)
            bubble(FerryShape.frontBubble, front, Offset(368f, 368f), 120f, Offset(359.5f, 535f), boarding.front, 12 * k / s)
        }
    }
}

/**
 * A bubble in the account's colour with its mark in white, in the artboard's units: boarding (0 → 1), it rises
 * `rise` from the deck as it scales up from 0.6 around its tail's `base`.
 */
private fun DrawScope.bubble(path: Path, rider: Crossing.Rider, mark: Offset, fontSize: Float, base: Offset, boarded: Float, rise: Float) {
    val alpha = boarded.coerceIn(0f, 1f)
    if (alpha == 0f) return
    translate(0f, rise * (1 - boarded)) {
        scale(0.6f + 0.4f * boarded, base) {
            drawPath(path, rider.color.fillColor, alpha = alpha)
            drawMark(rider.mark, mark, fontSize, alpha)
        }
    }
}

private fun DrawScope.drawMark(text: String, center: Offset, fontSize: Float, alpha: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.White.copy(alpha = alpha).toArgb()
        textSize = fontSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val metrics = paint.fontMetrics
    drawContext.canvas.nativeCanvas.drawText(text, center.x, center.y - (metrics.ascent + metrics.descent) / 2, paint)
}

/** Half-sine arches along `y`, each `half` long (negative runs right to left), alternately up and down (iOS `addArches`). */
private fun Path.arches(x: Float, y: Float, half: Float, amplitude: Float, count: Int) {
    for (i in 0 until count) {
        val start = x + i * half
        val crest = y + (if (i % 2 == 0) -1 else 1) * 1.333f * amplitude
        cubicTo(start + 0.364f * half, crest, start + 0.636f * half, crest, start + half, y)
    }
}

/** O1's scene as plain logic (iOS `Crossing`): where the moving parts are. Lengths in dp at scale 1 (full width × 224). */
object Crossing {
    const val HEIGHT = 224f
    const val HORIZON = 132f
    /** The sea band's wavy lower edge. */
    const val EDGE = 214f
    /** The icon's artboard → the scene (the hull is 700 units, so 210 dp). */
    const val FERRY_SCALE = 0.3f
    const val EDGE_WAVELENGTH = 131f
    val SWELL_WAVELENGTHS = listOf(120f, 160f)

    /** Where the keel's centre (the artboard's 512, 700) sits in the scene: the deck (572) is 6 below the horizon. */
    fun keel(scale: Float) = (HORIZON + 6) * scale + (700 - 572) * FERRY_SCALE * scale

    class Rider(val color: AccountColor, val mark: String)

    /** Front and back bubble: Welcome shows only before any account, so always iOS's samples (Ocean "A", Ember "S"). */
    val samples = Rider(AccountColor.OCEAN, "A") to Rider(AccountColor.EMBER, "S")

    /** Where the moving parts are; the default is at rest. */
    data class Pose(
        /** dp, the ferry and its bubbles. */
        val bob: Float = 0f,
        /** Degrees around the keel. */
        val roll: Float = 0f,
        /** How far each swell line has drifted left, wrapping every wavelength. */
        val swell: List<Float> = listOf(0f, 0f),
        /** The same for the band's lower edge. */
        val edge: Float = 0f,
        /** The wake's two strokes' opacity. */
        val wake: List<Float> = listOf(0.5f, 0.5f),
    )

    /** `t` seconds into the crossing; null is at rest. */
    fun pose(t: Float?): Pose {
        if (t == null) return Pose()
        val wake = 0.175f * sin(2 * PI.toFloat() * t / 2.4f)
        return Pose(
            bob = 1.5f * sin(2 * PI.toFloat() * t / 4.8f),
            roll = 0.8f * sin(2 * PI.toFloat() * t / 6.4f + 1.1f),
            swell = listOf((t * 8) % SWELL_WAVELENGTHS[0], (t * 5) % SWELL_WAVELENGTHS[1]),
            edge = (t * 3) % EDGE_WAVELENGTH,
            wake = listOf(0.525f + wake, 0.525f - wake),
        )
    }

    /** How far each part has come aboard (0 → 1, a spring may pass 1): the ferry, then the front and back bubbles. */
    data class Boarding(val ferry: Float, val front: Float, val back: Float)

    /**
     * The entrance, once per appearance (none at rest): the ferry glides in over 0.7 s, the bubbles board with a
     * spring (0.55 s, bounce 0.3), the front at 0.25 s and the back at 0.40 s.
     */
    fun boarding(t: Float?): Boarding {
        if (t == null) return Boarding(1f, 1f, 1f)
        return Boarding(spring(t, 0.7f, 0f), spring(t - 0.25f, 0.55f, 0.3f), spring(t - 0.40f, 0.55f, 0.3f))
    }

    /** SwiftUI's `spring(duration:bounce:)` from 0 toward 1, `t` seconds after it starts (0 before). */
    fun spring(t: Float, duration: Float, bounce: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 4 * duration) return 1f // settled: the bubbles' drawing stops changing
        val w = 2 * PI.toFloat() / duration
        val damping = 1 - bounce
        if (damping >= 1f) return 1 - (1 + w * t) * exp(-w * t)
        val wd = w * sqrt(1 - damping * damping)
        return 1 - exp(-damping * w * t) * (cos(wd * t) + damping * w / wd * sin(wd * t))
    }

    enum class Motion {
        /** Drawn once at rest: a level ferry, bubbles aboard, no entrance. */
        STILL,
        /** Frozen where it is: the app isn't in front. */
        PAUSED,
        RUNNING,
    }

    /** iOS's rules: still with animations off (Android's Reduce Motion) or battery saver on (Low Power Mode). */
    fun motion(animationsOff: Boolean, powerSave: Boolean, resumed: Boolean): Motion = when {
        animationsOff || powerSave -> Motion.STILL
        resumed -> Motion.RUNNING
        else -> Motion.PAUSED
    }
}

/**
 * `Crossing.motion` for the screen (iOS `AmbientMotion`, shared with O4's deck): the system's animator duration scale
 * (read again each time the app comes back), battery saver (as it changes), and whether the app is resumed.
 */
@Composable
fun rememberMotion(): Crossing.Motion {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var powerSave by remember { mutableStateOf(power.isPowerSaveMode) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { powerSave = power.isPowerSaveMode }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val state by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = state.isAtLeast(Lifecycle.State.RESUMED)
    val animationsOff = remember(resumed) { !ValueAnimator.areAnimatorsEnabled() }
    return Crossing.motion(animationsOff, powerSave, resumed)
}

/** Seconds the scene has run, counted only while `motion` is RUNNING (else frozen), at most 30 times a second (as iOS). */
@Composable
fun rememberCrossingClock(motion: Crossing.Motion): FloatState {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motion) {
        if (motion != Crossing.Motion.RUNNING) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1e9f
                if (dt >= 0.032f) {
                    clock.floatValue += minOf(dt, 0.1f) // a stall doesn't jump the sea
                    last = now
                }
            }
        }
    }
    return clock
}

/**
 * The app icon's shapes in its 1024 artboard (iOS `FerryShape`): the path data of the iOS repo's
 * `docs/design/ferry-icon-color.svg`, verbatim, as in `res/drawable/ic_launcher_foreground.xml`. Redo both when it changes.
 */
object FerryShape {
    val backBubble by lazy { path("M650 216 H722 C766.2 216 802 251.8 802 296 V316 C802 368 795.2 402.1 815.6 436.8 C785.7 418.4 727.2 396 666 396 H650 C605.8 396 570 360.2 570 316 V296 C570 251.8 605.8 216 650 216Z") }
    val frontBubble by lazy { path("M306 252 H430 C485.2 252 530 296.8 530 352 V384 C530 439.2 485.2 484 430 484 H376 C299.5 484 226.4 512 189 535 C214.5 491.6 206 449 206 384 V352 C206 296.8 250.8 252 306 252Z") }
    val hull by lazy { path("M162 572 H862 C836 614 808 654 784 684 C776 694 766 700 752 700 H250 C208 700 178 648 162 572Z") }
    /** A stroke's centre line (36 units wide in the icon). */
    val wave by lazy { path("M242 794 C291.5 772.7 328.5 772.7 378 794 C427.5 815.3 464.5 815.3 514 794 C563.5 772.7 600.5 772.7 650 794 C699.5 815.3 736.5 815.3 786 794") }

    private fun path(d: String) = addPathNodes(d).toPath()
}
