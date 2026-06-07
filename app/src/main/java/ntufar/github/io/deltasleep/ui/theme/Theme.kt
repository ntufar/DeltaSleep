package ntufar.github.io.deltasleep.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val scheme = darkColorScheme(
    primary = Color(0xFF42A5F5),
    secondary = Color(0xFF1565C0),
    background = Color(0xFF0A0E1A),
    surface = Color(0xFF12192B),
    onBackground = Color(0xFFE0E8FF),
    onSurface = Color(0xFFE0E8FF),
)

@Composable
fun DeltaSleepTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
