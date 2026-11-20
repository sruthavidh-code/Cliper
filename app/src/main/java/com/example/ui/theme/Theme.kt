package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ClipPulseDarkColorScheme = darkColorScheme(
    primary = ElectricViolet,
    onPrimary = Color.White,
    primaryContainer = ElectricVioletDark,
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = NeonLime,
    onSecondary = NeonLimeDark,
    secondaryContainer = Color(0xFF28390C),
    onSecondaryContainer = NeonLime,
    tertiary = NeonCyan,
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF164E63),
    onTertiaryContainer = Color(0xFFCFFAFE),
    background = CharcoalBg,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = CharcoalBg,
    surfaceContainerLow = Color(0xFF11141C),
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerHighest = SurfaceHighlight,
    outline = BorderHairline,
    outlineVariant = Color(0xFF1E2436),
    error = ScoreCoral,
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF3F1320),
    onErrorContainer = Color(0xFFFFD6DF)
)

private val ClipPulseShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun ClipPulseTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ClipPulseDarkColorScheme,
        typography = ClipPulseTypography,
        shapes = ClipPulseShapes,
        content = content
    )
}
