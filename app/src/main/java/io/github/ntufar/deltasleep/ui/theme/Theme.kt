package io.github.ntufar.deltasleep.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.ntufar.deltasleep.settings.AppTheme

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF42A5F5),
    secondary = Color(0xFF1565C0),
    background = Color(0xFF0A0E1A),
    surface = Color(0xFF12192B),
    onBackground = Color(0xFFE0E8FF),
    onSurface = Color(0xFFE0E8FF),
)

/**
 * AMOLED-black: pure black background/surface. Matters for a screen that is
 * on at night next to a sleeping user — every non-black pixel is glare.
 */
private val AmoledScheme = darkColorScheme(
    primary = Color(0xFF42A5F5),
    secondary = Color(0xFF1565C0),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF0A0A0A),
    onBackground = Color(0xFFE0E8FF),
    onSurface = Color(0xFFE0E8FF),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF42A5F5),
    background = Color(0xFFF5F7FC),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0A0E1A),
    onSurface = Color(0xFF0A0E1A),
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
