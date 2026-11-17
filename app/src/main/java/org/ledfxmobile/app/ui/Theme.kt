package org.ledfxmobile.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFFFF5C7A),
    onPrimary = Color(0xFF2B0010),
    primaryContainer = Color(0xFF5C1027),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFF8C7BFF),
    onSecondary = Color(0xFF14004A),
    secondaryContainer = Color(0xFF2E2563),
    onSecondaryContainer = Color(0xFFE4DFFF),
    tertiary = Color(0xFF4DD8E6),
    background = Color(0xFF0D0D12),
    onBackground = Color(0xFFE6E1E6),
    surface = Color(0xFF0D0D12),
    onSurface = Color(0xFFE6E1E6),
    surfaceVariant = Color(0xFF23222B),
    onSurfaceVariant = Color(0xFFC9C4D0),
    surfaceContainerLowest = Color(0xFF09090D),
    surfaceContainerLow = Color(0xFF15151B),
    surfaceContainer = Color(0xFF1A1A21),
    surfaceContainerHigh = Color(0xFF212029),
    surfaceContainerHighest = Color(0xFF2B2A34),
    outline = Color(0xFF6F6B78),
    outlineVariant = Color(0xFF34333D),
)

@Composable
fun LedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
