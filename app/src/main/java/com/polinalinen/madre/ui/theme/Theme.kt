package com.polinalinen.madre.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ═══ Dark theme colors (current aesthetic) ═══
private val DarkColors = darkColorScheme(
    primary = AccentGold,
    onPrimary = DarkBg,
    primaryContainer = AccentBrown,
    onPrimaryContainer = AccentCream,
    secondary = AccentRose,
    onSecondary = DarkBg,
    secondaryContainer = StatusWait,
    onSecondaryContainer = Color(0xFFF5EDE4),
    tertiary = AccentCream,
    background = DarkBg,
    onBackground = Color(0xFFF5EDE4),
    surface = DarkCard,
    onSurface = Color(0xFFF5EDE4),
    surfaceVariant = DarkCardHover,
    onSurfaceVariant = Color(0xFFB5A597),
    outline = Color(0xFF3A302A),
    outlineVariant = AccentBrown,
)

// ═══ Light theme colors (warm bakery) ═══
private val LightColors = lightColorScheme(
    primary = LightAccentGold,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = LightAccentBrown,
    onPrimaryContainer = Color(0xFF3A2A1A),
    secondary = LightAccentRose,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4EDED),
    onSecondaryContainer = Color(0xFF3A2A1A),
    tertiary = LightAccentCream,
    background = LightBg,
    onBackground = Color(0xFF3A2A1A),
    surface = LightCard,
    onSurface = Color(0xFF3A2A1A),
    surfaceVariant = LightCardHover,
    onSurfaceVariant = Color(0xFF7A6A58),
    outline = Color(0xFFD9C9B0),
    outlineVariant = LightAccentBrown,
)

// ═══ Extended palette exposed via CompositionLocal ═══
data class ExtendedColors(
    val accentGold: Color,
    val accentRose: Color,
    val accentCream: Color,
    val accentBrown: Color,
    val statusAction: Color,
    val statusWait: Color,
    val statusCompleted: Color,
    val timerBackground: Color,
    val timerUrgent: Color,
    val dividerColor: Color,
    val difficultyEasy: Color,
    val difficultyMedium: Color,
    val difficultyHard: Color,
)

private val DarkExtended = ExtendedColors(
    accentGold = AccentGold,
    accentRose = AccentRose,
    accentCream = AccentCream,
    accentBrown = AccentBrown,
    statusAction = StatusAction,
    statusWait = StatusWait,
    statusCompleted = StatusCompleted,
    timerBackground = Color(0xFF2A2420),
    timerUrgent = TimerUrgent,
    dividerColor = Color(0xFF3A302A),
    difficultyEasy = DifficultyEasy,
    difficultyMedium = DifficultyMedium,
    difficultyHard = DifficultyHard,
)

private val LightExtended = ExtendedColors(
    accentGold = LightAccentGold,
    accentRose = LightAccentRose,
    accentCream = LightAccentCream,
    accentBrown = LightAccentBrown,
    statusAction = LightAccentGold,
    statusWait = Color(0xFF5B8A8F),
    statusCompleted = Color(0xFF6B8F5B),
    timerBackground = Color(0xFFE8D5B8),
    timerUrgent = LightAccentRose,
    dividerColor = Color(0xFFD9C9B0),
    difficultyEasy = Color(0xFF6B8F5B),
    difficultyMedium = LightAccentGold,
    difficultyHard = LightAccentRose,
)

val LocalExtendedColors = staticCompositionLocalOf { DarkExtended }

@Composable
fun LevitoMadreTheme(
    isDarkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (isDarkTheme) DarkColors else LightColors
    val extended = if (isDarkTheme) DarkExtended else LightExtended

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = LevitoTypography,
            content = content
        )
    }
}
