package io.github.ntufar.deltasleep.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.ntufar.deltasleep.settings.AppTheme

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0A1A33),
    primaryContainer = Color(0xFF1B3A66),
    onPrimaryContainer = Color(0xFFD9E5FF),
    secondary = Color(0xFF7DD3C0),
    onSecondary = Color(0xFF06302A),
    tertiary = Color(0xFFB388FF),
    background = Color(0xFF0B0F19),
    onBackground = Color(0xFFE3E9F7),
    surface = Color(0xFF121826),
    onSurface = Color(0xFFE3E9F7),
    surfaceVariant = Color(0xFF1C2438),
    onSurfaceVariant = Color(0xFFA9B6D1),
    outline = Color(0xFF2A3550),
    outlineVariant = Color(0xFF1E2942),
    error = Color(0xFFFFB4AB),
)

/**
 * AMOLED-black: pure black background/surface. Matters for a screen that is
 * on at night next to a sleeping user — every non-black pixel is glare.
 */
private val AmoledScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1B3A66),
    onPrimaryContainer = Color(0xFFD9E5FF),
    secondary = Color(0xFF7DD3C0),
    tertiary = Color(0xFFB388FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE3E9F7),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE3E9F7),
    surfaceVariant = Color(0xFF101014),
    onSurfaceVariant = Color(0xFFA9B6D1),
    outline = Color(0xFF26262B),
    outlineVariant = Color(0xFF17171B),
    error = Color(0xFFFFB4AB),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2456A6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E5FF),
    onPrimaryContainer = Color(0xFF0A1A33),
    secondary = Color(0xFF0E6B5C),
    tertiary = Color(0xFF6A4FC7),
    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF101828),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFE8EDF6),
    onSurfaceVariant = Color(0xFF4A5878),
    outline = Color(0xFFCBD5E8),
    outlineVariant = Color(0xFFE1E8F5),
)

@Composable
fun DeltaSleepTheme(theme: AppTheme = AppTheme.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (theme) {
        AppTheme.LIGHT -> false
        AppTheme.DARK, AppTheme.AMOLED_BLACK -> true
        AppTheme.SYSTEM -> isSystemInDarkTheme()
    }
    val scheme = when {
        theme == AppTheme.AMOLED_BLACK -> AmoledScheme
        dark -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
