package io.github.ntufar.deltasleep.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared back navigation control: a [TextButton] with a Material arrow icon
 * and label. A single home for it so the arrow glyph can never drift back
 * into a font-fallback text arrow (which renders in a mismatched serif face
 * on some devices).
 */
@Composable
fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onBack, modifier = modifier) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text("Back")
    }
}
