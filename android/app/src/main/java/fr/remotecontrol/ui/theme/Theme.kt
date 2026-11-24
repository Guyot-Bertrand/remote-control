package fr.remotecontrol.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// Rôles Material 3 du thème sombre, d'après la planche « Material 3 » des maquettes.
private val RcColorScheme = darkColorScheme(
    primary = RcColors.accent,
    onPrimary = RcColors.onAccent,
    primaryContainer = RcColors.accentSoft,
    onPrimaryContainer = RcColors.accentStrong,
    secondary = RcColors.accentStrong,
    onSecondary = RcColors.onAccent,
    secondaryContainer = RcColors.accentSoft,
    onSecondaryContainer = RcColors.accentStrong,
    background = RcColors.bg,
    onBackground = RcColors.text,
    surface = RcColors.bg,
    onSurface = RcColors.text,
    surfaceVariant = RcColors.surface2,
    onSurfaceVariant = RcColors.text2,
    surfaceContainerLowest = RcColors.bg,
    surfaceContainerLow = RcColors.surface,
    surfaceContainer = RcColors.surface,
    surfaceContainerHigh = RcColors.surface2,
    surfaceContainerHighest = RcColors.surface3,
    outline = RcColors.text3,
    outlineVariant = RcColors.line,
    error = RcColors.warn,
    onError = RcColors.onAccent,
    errorContainer = RcColors.warnSoft,
    onErrorContainer = RcColors.warn,
)

private val RcShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Thème unique, sombre (pas de variante claire). */
@Composable
fun RcTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RcColorScheme, typography = RcTypography, shapes = RcShapes, content = content)
}
