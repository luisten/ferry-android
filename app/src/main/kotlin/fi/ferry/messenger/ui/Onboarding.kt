package fi.ferry.messenger.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.CompositionLocalProvider
import fi.ferry.messenger.model.AccountIdentity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.ferry.messenger.AppModel
import fi.ferry.messenger.model.BridgeAPI
import fi.ferry.messenger.model.BridgeAPI.Login.Health
import fi.ferry.messenger.model.Inbox

// Onboarding after the login (DESIGN §2.1, iOS `OnboardingView`): O4 connecting, O5 name, colour and mark, O6 "Add
// another account?" after the first. O1 is Welcome.kt, O3 LoginScreen.kt.

/** A full-width capsule button on the bottom bar, iOS's prominent one (17 sp semibold, 50 dp). */
@Composable
fun BarButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 50.dp), enabled = enabled) {
        Text(text, style = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
    }
}

/**
 * O4 (DESIGN O4, iOS `ConnectingView`): logged in ✓ → connected to Instagram ✓ → syncing recent chats, with the number
 * of chats so far, from the engine's states. Continue unlocks at step 2; Back moves on too, as Continue would.
 */
@Composable
fun ConnectingScreen(id: String, retry: () -> Unit, next: () -> Unit) {
    val login = AppModel.accounts.firstOrNull { it.id == id }
    val health = login?.health ?: Health.Connecting
    val connected = Connecting.isConnected(health)
    val chats = AppModel.rooms.values.count { it.bridge?.receiver == id && Inbox.isVisible(it, AppModel.loginIDs) }
    val steps = Connecting.steps(health)
    val haptics = LocalHapticFeedback.current
    var wasConnected by remember { mutableStateOf(connected) }
    LaunchedEffect(connected) {
        if (connected && !wasConnected) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        wasConnected = connected
    }
    BackHandler(onBack = next)
    // The account on deck bobs while the first sync runs, with Welcome's rules (iOS `AmbientMotion`).
    val motion = rememberMotion()
    val bobbing = health == Health.Syncing && motion != Crossing.Motion.STILL
    val clock = rememberCrossingClock(if (bobbing) motion else Crossing.Motion.PAUSED)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Box(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)) {
                BarButton("Continue", enabled = connected, onClick = next)
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            FerryDeck(150.dp, Modifier.padding(top = 32.dp), bob = { if (bobbing) Crossing.pose(clock.floatValue).bob else 0f }) {
                // A neutral ring until O5 picks a colour.
                Box(Modifier.size(96.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                    Picture(login?.profile?.avatar, login?.displayName.orEmpty(), 88.dp)
                }
            }
            Text(login?.profile?.username?.let { "Connected as @$it" } ?: "Connecting…", Modifier.semantics { heading() },
                style = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StepRow("Logged in", steps[0])
                StepRow("Connected to Instagram", steps[1])
                StepRow("Syncing recent chats", steps[2], Connecting.chats(chats, health))
            }
            if (health.needsUser) ConnectFailure(login, retry)
            else Text("You can start chatting while older messages load.", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun StepRow(title: String, state: Connecting.Step, detail: String? = null) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = state.spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (state) {
                // Status tokens, not Material's green and red.
                Connecting.Step.DONE -> Icon(Icons.done, null, Modifier.size(24.dp), tint = Status.ok)
                Connecting.Step.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Connecting.Step.WAITING -> Icon(Icons.circle, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.outline)
                Connecting.Step.FAILED -> Icon(Icons.failed, null, Modifier.size(24.dp), tint = Status.error)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            val waiting = state == Connecting.Step.WAITING
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (waiting) FontWeight.Normal else FontWeight.SemiBold,
                color = if (waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** DESIGN O4's errors: "Couldn't finish connecting", Try Again (a re-login of this account) and Copy Details. */
@Composable
private fun ConnectFailure(login: BridgeAPI.Login?, retry: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val details = Connecting.details(login)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Couldn't finish connecting", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Text(if (login?.health == Health.NeedsLogin) "Instagram ended the session right away. Log in again to finish."
            else "Instagram reported an error. Try again, or copy the details for a bug report.",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(retry) { Text("Try Again") }
            if (details.isNotEmpty()) FilledTonalButton({
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Details", details))
                copied = true
            }) { Text(if (copied) "Copied" else "Copy Details") }
        }
    }
}

/**
 * O5 "Name this account" (DESIGN O5, iOS `NameAccountView`), right after a new account connects: a grouped page with
 * the editor, Save in the top bar and, for the first account, "Add another account" as the last row; O6 then offers a
 * second. `done(addAnother)`. Back skips naming (the defaults stay).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameAccountScreen(id: String, done: (addAnother: Boolean) -> Unit) {
    val login = AppModel.accounts.firstOrNull { it.id == id }
    val start = AppModel.identities[id]
    var draft by remember(id) { mutableStateOf(start) }
    var offerAnother by remember { mutableStateOf(false) }
    val first = AppModel.accounts.size <= 1
    BackHandler { done(false) }
    if (offerAnother) return AddAnotherScreen(id, connect = { done(true) }, notNow = { done(false) })
    val identity = draft ?: return done(false)
    val valid = AccountIdentity.problems(identity, id, AppModel.identities).isEmpty()
    fun save(): Boolean {
        if (!valid) return false
        AppModel.setIdentity(id, identity)
        return true
    }
    Scaffold(
        containerColor = Grouped.page,
        topBar = {
            TopAppBar(
                title = { Text("Name this account") },
                actions = { TextButton({ if (save()) { if (first) offerAnother = true else done(false) } }, enabled = valid) { Text("Save") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Grouped.page, scrolledContainerColor = Grouped.page),
            )
        },
    ) { padding ->
        CompositionLocalProvider(LocalMarkRing provides Grouped.card) {
            Column(
                Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Pick a name, color and mark for ${login?.profile?.username?.let { "@$it" } ?: "this account"}. You'll see them on every chat from this account.",
                    Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                IdentityEditor(id, identity, login) { draft = it }
                // A row, not a bottom bar: a bar covered the colours and Mark on iOS.
                if (first) GroupedSection { ActionRow("Add another account", Icons.add, enabled = valid) { if (save()) done(true) } }
            }
        }
    }
}

/**
 * O6 "Add another account?" (DESIGN O6, iOS `AddAnotherView`), after the first account only: your account and a free
 * seat side by side on one deck, then the question, with the two buttons at the bottom.
 */
@Composable
private fun AddAnotherScreen(id: String, connect: () -> Unit, notNow: () -> Unit) {
    val login = AppModel.accounts.firstOrNull { it.id == id }
    val identity = AppModel.identities[id]
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BarButton("Connect another account", onClick = connect)
                TextButton(notNow, Modifier.heightIn(min = 48.dp)) { Text("Not now", style = MaterialTheme.typography.bodyLarge) }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FerryDeck(210.dp, Modifier.padding(top = 80.dp, bottom = 12.dp)) {
                // 16 dp between the pictures: the ring takes 4 of them outside the avatar's.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountAvatar(identity, login?.profile?.avatar, identity?.name ?: login?.displayName.orEmpty(), 88.dp, mark = true)
                    Seat()
                }
            }
            Text("Add another account?", Modifier.semantics { heading() },
                style = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            Text("Connect a second Instagram account. Each chat stays tied to the account it belongs to.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** O6's free seat: a dashed 88 dp circle with a plus. */
@Composable
private fun Seat() {
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(88.dp).background(MaterialTheme.colorScheme.background, CircleShape).drawBehind {
            val width = 2.dp.toPx()
            drawCircle(line, radius = size.minDimension / 2 - width / 2,
                style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
        },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.add, null, Modifier.size(32.dp), tint = line) }
}

/** O4's steps as plain logic. */
object Connecting {
    enum class Step(val spoken: String) { WAITING("waiting"), RUNNING("in progress"), DONE("done"), FAILED("failed") }

    /** Connected enough to go on: the sync may still run. */
    fun isConnected(health: Health) = health == Health.Connected || health == Health.Syncing

    /** Logged in (always, by now), connected to Instagram, syncing recent chats. */
    fun steps(health: Health): List<Step> = listOf(
        Step.DONE,
        when {
            health.needsUser -> Step.FAILED
            isConnected(health) -> Step.DONE
            else -> Step.RUNNING
        },
        when (health) {
            Health.Connected -> Step.DONE
            Health.Syncing -> Step.RUNNING
            else -> Step.WAITING
        },
    )

    /** "12 chats so far", "1 chat" once synced; nothing before the first. */
    fun chats(count: Int, health: Health): String? {
        if (count == 0) return null
        return (if (count == 1) "1 chat" else "$count chats") + if (health == Health.Connected) "" else " so far"
    }

    /** The engine's state and message, for a bug report. */
    fun details(login: BridgeAPI.Login?): String = listOfNotNull(login?.state?.stateEvent, login?.state?.message).joinToString(": ")
}

/**
 * The deck that carries the account through onboarding (iOS `FerryDeck`, DESIGN O4, O6): the icon's hull in the fill
 * colour, 4 dp under what's aboard, over a 2 dp wave. `bob` (dp) moves what's aboard and the hull; the wave stays.
 * Decorative: hidden from TalkBack, with what's aboard.
 */
@Composable
fun FerryDeck(width: Dp, modifier: Modifier = Modifier, bob: () -> Float = { 0f }, aboard: @Composable () -> Unit) {
    val hull = MaterialTheme.colorScheme.surfaceContainerHighest
    val wave = MaterialTheme.colorScheme.outline
    Column(modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.graphicsLayer { translationY = bob().dp.toPx() }, horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            aboard()
            // The hull alone: the artboard's (162, 572, 700 × 128).
            Canvas(Modifier.size(width, width * 128f / 700f)) {
                scale(size.width / 700f, Offset.Zero) { translate(-162f, -572f) { drawPath(FerryShape.hull, hull) } }
            }
        }
        // The wave's centre line: (242, 778, 544 × 32).
        Canvas(Modifier.size(width * 0.78f, width * 0.78f * 32f / 544f)) {
            val k = size.width / 544f
            scale(k, Offset.Zero) {
                translate(-242f, -778f) { drawPath(FerryShape.wave, wave, style = Stroke(2.dp.toPx() / k, cap = StrokeCap.Round)) }
            }
        }
    }
}
