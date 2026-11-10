package com.btcvolume.notifier

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A6A00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE9A8),
    onPrimaryContainer = Color(0xFF3A2C00),
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF1E2329),
    surface = Color.White,
    onSurface = Color(0xFF1E2329),
    surfaceVariant = Color(0xFFF0F1F4),
    onSurfaceVariant = Color(0xFF5E6673),
    secondaryContainer = Color(0xFFE6E8EC),
    onSecondaryContainer = Color(0xFF1E2329),
    outline = Color(0xFFC7CBD1)
)

val UpColor = Color(0xFF0ECB81)
val DownColor = Color(0xFFF6465D)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
