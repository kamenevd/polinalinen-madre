package com.polinalinen.madre.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val LevitoColorScheme = darkColorScheme(
    primary = AccentGold,
    onPrimary = BackgroundDark,
    primaryContainer = AccentBrown,
    onPrimaryContainer = AccentCream,

    secondary = AccentRose,
    onSecondary = BackgroundDark,
    secondaryContainer = StatusWait,
    onSecondaryContainer = TextPrimary,

    tertiary = AccentCream,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = BackgroundCard,
    onSurface = TextPrimary,
    surfaceVariant = BackgroundCardHover,
    onSurfaceVariant = TextSecondary,

    outline = DividerColor,
    outlineVariant = AccentBrown,
)

@Composable
fun LevitoMadreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LevitoColorScheme,
        typography = LevitoTypography,
        content = content
    )
}
