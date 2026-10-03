package de.axelcypher.asmr.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Immer dunkel und gedämpft: die App wird meist im Bett und im Dunkeln benutzt.
private val NightColors = darkColorScheme(
    primary = Color(0xFFB8A6E0),
    onPrimary = Color(0xFF241B3A),
    primaryContainer = Color(0xFF3A2F55),
    onPrimaryContainer = Color(0xFFE6DDFF),
    secondary = Color(0xFF9DB4C8),
    background = Color(0xFF0D0D12),
    onBackground = Color(0xFFDCD8E4),
    surface = Color(0xFF0D0D12),
    onSurface = Color(0xFFDCD8E4),
    surfaceVariant = Color(0xFF1C1B24),
    onSurfaceVariant = Color(0xFFA9A5B3),
    surfaceContainer = Color(0xFF16151D),
)

@Composable
fun AsmrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NightColors, content = content)
}
