package app.eclipse.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.eclipse.android.model.AppearanceMode

/** Палитра из iOS-версии. */
data class EclipseColors(
    val bg: Color, val card: Color, val text: Color, val muted: Color,
    val accent: Color, val accentLight: Color, val border: Color, val field: Color,
    val good: Color, val warn: Color, val bad: Color,
)

private val DarkColors = EclipseColors(
    bg = Color(0xFF0D0B1A), card = Color(0xFF15121F), text = Color.White, muted = Color(0xFF9B90CC),
    accent = Color(0xFF7C5CFC), accentLight = Color(0xFFC4ABFF), border = Color(0x17FFFFFF), field = Color(0x47000000),
    good = Color(0xFF4ADE80), warn = Color(0xFFFBBF24), bad = Color(0xFFFF8A8A),
)
private val LightColors = EclipseColors(
    bg = Color(0xFFF5F2FF), card = Color.White, text = Color(0xFF1B1635), muted = Color(0xFF6B6190),
    accent = Color(0xFF7C5CFC), accentLight = Color(0xFF5B3FD6), border = Color(0x1F1B1635), field = Color(0x0F1B1635),
    good = Color(0xFF16A34A), warn = Color(0xFFD97706), bad = Color(0xFFDC2626),
)

val LocalEclipse = staticCompositionLocalOf { DarkColors }

object EC {
    val c: EclipseColors @Composable get() = LocalEclipse.current
}

@Composable
fun EclipseTheme(mode: AppearanceMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        AppearanceMode.DARK -> true
        AppearanceMode.LIGHT -> false
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
    }
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = Color.White, background = c.bg, onBackground = c.text,
        surface = c.card, onSurface = c.text, surfaceVariant = c.card, onSurfaceVariant = c.muted,
        outline = c.border, secondaryContainer = c.accent.copy(alpha = 0.25f), onSecondaryContainer = c.text,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = Color.White, background = c.bg, onBackground = c.text,
        surface = c.card, onSurface = c.text, surfaceVariant = c.card, onSurfaceVariant = c.muted,
        outline = c.border, secondaryContainer = c.accent.copy(alpha = 0.15f), onSecondaryContainer = c.text,
    )
    CompositionLocalProvider(LocalEclipse provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
