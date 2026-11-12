package com.btcvolume.notifier

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Mode d'apparence choisi : SYSTEM, LIGHT ou DARK. */
object ThemeState {
    val mode = mutableStateOf("SYSTEM")
}

val LocalDark = staticCompositionLocalOf { true }

// Piste A : sombre pro (accent jaune)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFF0B90B),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF3A2F05),
    onPrimaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF0B0E11),
    onBackground = Color(0xFFEAECEF),
    surface = Color(0xFF161A1E),
    onSurface = Color(0xFFEAECEF),
    surfaceVariant = Color(0xFF1E2329),
    onSurfaceVariant = Color(0xFF9AA4B2),
    secondaryContainer = Color(0xFF2B3139),
    onSecondaryContainer = Color(0xFFEAECEF),
    outline = Color(0xFF474D57)
)

// Piste C : clair épuré (accent turquoise)
private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F4F1),
    onPrimaryContainer = Color(0xFF0B4F49),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color.White,
    onSurfaceVariant = Color(0xFF5B6472),
    secondaryContainer = Color(0xFFE3F4F1),
    onSecondaryContainer = Color(0xFF0F766E),
    outline = Color(0xFFD1D5DB)
)

/** Hausse / baisse : teintes adaptées à chaque mode pour rester lisibles. */
val UpColor: Color
    @Composable get() = if (LocalDark.current) Color(0xFF0ECB81) else Color(0xFF047857)

val DownColor: Color
    @Composable get() = if (LocalDark.current) Color(0xFFF6465D) else Color(0xFFB91C1C)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val mode = ThemeState.mode.value
    val dark = when (mode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) DarkColors else LightColors
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            window.statusBarColor = scheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
