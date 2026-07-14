package com.mydream.calllogger.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Menuthere brand theme — warm orange on cream. Mirrors the Cravings/Menuthere
 * palette (orange-600 #EA580C brand, warm cream surfaces).
 */

// Brand ramp
private val Orange600 = Color(0xFFEA580C)
private val Orange300 = Color(0xFFFDBA8C)
private val Orange100 = Color(0xFFFFE7D3)
private val Orange900 = Color(0xFF7C2D12)

private val MenuthereLight = lightColorScheme(
    primary = Orange600,
    onPrimary = Color.White,
    primaryContainer = Orange100,
    onPrimaryContainer = Orange900,
    secondary = Color(0xFFB45309),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEAD5),
    onSecondaryContainer = Color(0xFF6B3A0E),
    tertiary = Color(0xFFC2410C),
    onTertiary = Color.White,
    background = Color(0xFFFFF8F1),
    onBackground = Color(0xFF23190F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF23190F),
    surfaceVariant = Color(0xFFF3E6D8),
    onSurfaceVariant = Color(0xFF7A6A57),
    surfaceTint = Orange600,
    outline = Color(0xFFCDBBA6),
    outlineVariant = Color(0xFFE7D8C6),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    inverseSurface = Color(0xFF2E2419),
    inverseOnSurface = Color(0xFFFDEEE0),
)

private val MenuthereDark = darkColorScheme(
    primary = Orange300,
    onPrimary = Color(0xFF4A1C00),
    primaryContainer = Color(0xFF8A3A0E),
    onPrimaryContainer = Orange100,
    secondary = Color(0xFFEEBB8C),
    onSecondary = Color(0xFF4A2708),
    secondaryContainer = Color(0xFF6B3A0E),
    onSecondaryContainer = Color(0xFFFFEAD5),
    tertiary = Color(0xFFFDBA8C),
    onTertiary = Color(0xFF4A1C00),
    background = Color(0xFF1A140F),
    onBackground = Color(0xFFEDE0D3),
    surface = Color(0xFF221A13),
    onSurface = Color(0xFFEDE0D3),
    surfaceVariant = Color(0xFF4E4539),
    onSurfaceVariant = Color(0xFFD3C2AE),
    surfaceTint = Orange300,
    outline = Color(0xFF9C8B79),
    outlineVariant = Color(0xFF4E4539),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    inverseSurface = Color(0xFFEDE0D3),
    inverseOnSurface = Color(0xFF2E2419),
)

@Composable
fun CallLoggerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) MenuthereDark else MenuthereLight

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
