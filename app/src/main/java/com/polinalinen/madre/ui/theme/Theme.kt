package com.polinalinen.madre.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Семантические токены, которые не укладываются в стандартную Material3 ColorScheme
 * (crust/sage/terracotta/cool + текстуры). Используем через AppColors.current,
 * а не хардкодим hex в экранах.
 */
data class MadreExtendedColors(
    val paper: Color,
    val cream: Color,
    val parchment: Color,
    val crust: Color,
    val caramel: Color,
    val amberDeep: Color,
    val sage: Color,
    val sageLight: Color,
    val terracotta: Color,
    val cool: Color,
    val espresso: Color,
    val cocoa: Color,
    val flour: Color,
)

private val LightExtendedColors = MadreExtendedColors(
    paper = Paper,
    cream = Cream,
    parchment = Parchment,
    crust = Crust,
    caramel = Caramel,
    amberDeep = AmberDeep,
    sage = Sage,
    sageLight = SageLight,
    terracotta = Terracotta,
    cool = Cool,
    espresso = Espresso,
    cocoa = Cocoa,
    flour = Flour,
)

private val LocalMadreColors = staticCompositionLocalOf { LightExtendedColors }

/** AppColors.current.crust вместо Color(0xFFC8763A) в коде экранов. */
object AppColors {
    val current: MadreExtendedColors
        @Composable get() = LocalMadreColors.current
}

private val MadreLightColorScheme = lightColorScheme(
    primary = Crust,
    onPrimary = Cream,
    secondary = Sage,
    onSecondary = Cream,
    tertiary = Terracotta,
    background = Paper,
    onBackground = Espresso,
    surface = Cream,
    onSurface = Espresso,
    surfaceVariant = Parchment,
    onSurfaceVariant = Cocoa,
    outline = Flour,
    error = Terracotta,
)

/**
 * Мадре v4 — только светлая тема ("Warm Paper").
 * isSystemInDarkTheme() сознательно НЕ читается для выбора палитры — decision #3:
 * "Тема: ТОЛЬКО светлая". Параметр оставлен только чтобы Preview-функции не ломались.
 */
@Composable
fun MadreTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // не используется для выбора палитры
    content: @Composable () -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalMadreColors provides LightExtendedColors) {
        MaterialTheme(
            colorScheme = MadreLightColorScheme,
            typography = MadreTypography,
            content = content
        )
    }
}
