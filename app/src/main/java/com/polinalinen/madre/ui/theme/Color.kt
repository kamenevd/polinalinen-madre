package com.polinalinen.madre.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// Мадре v4 — "Warm Paper" (единственная тема, только светлая).
// Источник: v4-design-decisions.md #3, #15, #25, #26.
// НЕ добавлять тёмную тему без явного решения Димы/Полины.
// НИКОГДА не хардкодить hex в composables — только через эти токены
// (или AppColors из Theme.kt).
// ═══════════════════════════════════════════════════════════════

// Поверхности
val Paper = Color(0xFFFAF6F0)       // Фон — тёплый лён
val Cream = Color(0xFFFFFCF6)       // Карточки
val Parchment = Color(0xFFF5EDE0)   // Вторичные поверхности

// Акценты
val Crust = Color(0xFFC8763A)       // Основной акцент — корочка хлеба
val Caramel = Color(0xFFD4956B)     // Вторичный акцент
val AmberDeep = Color(0xFF8B5A1E)   // Pressed-состояния

// Статусы закваски / рецептов
val Sage = Color(0xFF7A9159)        // Пик / здоровая / "Легко"
val SageLight = Color(0xFF9DB870)
val Terracotta = Color(0xFFC45A4E)  // Голодная / срочно / "Сложно"
val Cool = Color(0xFF5B8A8F)        // Спад / отдых

// Текст
val Espresso = Color(0xFF2D1F14)    // Основной текст
val Cocoa = Color(0xFF6B5544)       // Вторичный текст

// Разделители
val Flour = Color(0xFFD9CFC2)
