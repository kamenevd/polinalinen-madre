package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.theme.AppColors

/**
 * Оставшиеся заглушки — Feeding/Complete/Notifications.
 * Settings переехал в SettingsScreen.kt (реальный экран, 2026-07-21).
 * Заменяются после согласования мокапов «Живой книги» (DESIGN-V4.md экраны 5-7).
 */

@Composable
private fun PlaceholderScaffold(title: String, content: @Composable Column.() -> Unit) {
    Scaffold(containerColor = AppColors.current.paper) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = AppColors.current.espresso)
            content()
        }
    }
}

@Composable
fun BakingCompleteScreenPlaceholder(onHome: () -> Unit) {
    PlaceholderScaffold("Готово! (мокап на согласовании)") {
        Button(onClick = onHome) { Text("На главную") }
    }
}

@Composable
fun FeedingFormScreenPlaceholder(onSaved: () -> Unit) {
    PlaceholderScaffold("Покормить закваску (мокап на согласовании)") {
        Button(onClick = onSaved) { Text("Сохранить кормление") }
    }
}

@Composable
fun NotificationsScreenPlaceholder(onBack: () -> Unit) {
    PlaceholderScaffold("Уведомления (мокап на согласовании)") {
        Button(onClick = onBack) { Text("Назад") }
    }
}

