package fi.ferry.messenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import fi.ferry.messenger.model.AccountColor

// DESIGN §0.1, §8.1: the chrome is monochrome (black, white, greys); the only colour in the UI is an account's.
// Material's own tonal palette (purples) is replaced throughout, so no stock component brings colour in.

private val light = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E9EB), onPrimaryContainer = Color.Black,
    secondary = Color(0xFF6C6C72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E9EB), onSecondaryContainer = Color.Black,
    tertiary = Color.Black, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9E9EB), onTertiaryContainer = Color.Black,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    // `inkSecondary` (5.22:1 on white): the system grey fails AA for small text.
    surfaceVariant = Color(0xFFEFEFF0), onSurfaceVariant = Color(0xFF6C6C72),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF1C1C1E), inverseOnSurface = Color.White, inversePrimary = Color.White,
    error = Color(0xFFD70015), onError = Color.White,
    outline = Color(0xFFC6C6C8), outlineVariant = Color(0xFFE5E5EA),
    surfaceBright = Color.White, surfaceDim = Color(0xFFE5E5EA),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F7F8), surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFEBEBEF), surfaceContainerHighest = Color(0xFFE5E5EA),
)

private val dark = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color(0xFF2C2C2E), onPrimaryContainer = Color.White,
    secondary = Color(0xFFA1A1A8), onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2C2C2E), onSecondaryContainer = Color.White,
    tertiary = Color.White, onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF2C2C2E), onTertiaryContainer = Color.White,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E), onSurfaceVariant = Color(0xFFA1A1A8),
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFFF2F2F7), inverseOnSurface = Color.Black, inversePrimary = Color.Black,
    error = Color(0xFFFF453A), onError = Color.Black,
    outline = Color(0xFF48484A), outlineVariant = Color(0xFF38383A),
    surfaceBright = Color(0xFF2C2C2E), surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF111112), surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF242426), surfaceContainerHighest = Color(0xFF2C2C2E),
)

@Composable
fun FerryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}

/** Status glyphs and text (always with a glyph, never colour alone): 5+ on white and on #1C1C1E. */
object Status {
    val warn: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFFFF9F0A) else Color(0xFFB25000)
    val ok: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF30D158) else Color(0xFF1F7A35)
    val error: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFFFF453A) else Color(0xFFD70015)
}

/**
 * DESIGN §8.1's grouped pages (O5, A1, A2: iOS's inset-grouped `Form`): the page, and the cards on it
 * (`systemGroupedBackground`, `secondarySystemGroupedBackground`).
 */
object Grouped {
    val page: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color.Black else Color(0xFFF2F2F7)
    val card: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White
}

/**
 * Brand: scenery only (DESIGN §8.1 "Brand: the sea", principle 10): O1's sea, as iOS's `Scenery`. Never a tint, a
 * control, text or chrome: colour in the UI means account. The icon and the launch screen carry it in their own files.
 */
object Scenery {
    /** The sea band's top, at the horizon. */
    val seaHorizon: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF0F4561) else Color(0xFF1A7893)
    /** The sea band's lower edge. */
    val seaFloor: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF05192A) else Color(0xFF0D3F5B)
    /** The 40 dp above the horizon, fading in from the sky (the screen's background). */
    val seaHaze: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(0xFF0B1C27) else Color(0xFFE4F0F3)
    /** Swell and wake, at 35–70% opacity. */
    val foam = Color(0xFF9ED6E2)
}

/** The account's colour as a fill (the same in both modes, white text on it). */
val AccountColor.fillColor: Color get() = Color(fill)

/** Account-coloured text: lighter in dark mode, to stay above 4.5:1. */
val AccountColor.ink: Color @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) Color(darkInk) else Color(fill)

/** The fill at 12% (light) / 22% (dark): tags, the account scope's wash. */
val AccountColor.wash: Color @Composable @ReadOnlyComposable get() = Color(fill).copy(alpha = if (isSystemInDarkTheme()) 0.22f else 0.12f)
