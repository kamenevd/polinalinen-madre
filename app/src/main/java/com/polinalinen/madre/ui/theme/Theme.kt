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
    onPrimary = Color(0xFF1A1209),
    primaryContainer = AccentBrown,
    onPrimaryContainer = AccentCream,
    secondary = AccentRose,
    onSecondary = Color(0xFF1A1209),
    secondaryContainer = StatusWait,
    onSecondaryContainer = Color(0xFFF5EDE4),
    tertiary = AccentCream,
    background = Color(0xFF1A1209),
    onBackground = Color(0xFFF5EDE4),
    surface = Color(0xFF261C10),
    onSurface = Color(0xFFF5EDE4),
    surfaceVariant = Color(0xFF342818),
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
    background = Color(0xFFF5E6D3),
    onBackground = Color(0xFF3A2A1A),
    surface = Color(0xFFFDF5EB),
    onSurface = Color(0xFF3A2A1A),
    surfaceVariant = Color(0xFFF2E4CC),
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
    timerBackground = Color(0xFF342818),
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

/**
 * Convenience accessor for extended bakery colors inside @Composable functions.
 * Usage: AppColors.accentGold, AppColors.dividerColor, etc.
 */
object AppColors {
    val accentGold @Composable get() = LocalExtendedColors.current.accentGold
    val accentRose @Composable get() = LocalExtendedColors.current.accentRose
    val accentCream @Composable get() = LocalExtendedColors.current.accentCream
    val accentBrown @Composable get() = LocalExtendedColors.current.accentBrown
    val statusAction @Composable get() = LocalExtendedColors.current.statusAction
    val statusWait @Composable get() = LocalExtendedColors.current.statusWait
    val statusCompleted @Composable get() = LocalExtendedColors.current.statusCompleted
    val timerBackground @Composable get() = LocalExtendedColors.current.timerBackground
    val timerUrgent @Composable get() = LocalExtendedColors.current.timerUrgent
    val dividerColor @Composable get() = LocalExtendedColors.current.dividerColor
    val difficultyEasy @Composable get() = LocalExtendedColors.current.difficultyEasy
    val difficultyMedium @Composable get() = LocalExtendedColors.current.difficultyMedium
    val difficultyHard @Composable get() = LocalExtendedColors.current.difficultyHard
}

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
