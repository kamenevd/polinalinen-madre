package com.polinalinen.madre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    recipe: Recipe,
    onStartBaking: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalMinutes = recipe.timeline.sumOf { it.durationMinutes }
    val totalHours = totalMinutes / 60
    val remainMin = totalMinutes % 60
    val timeText = when {
        totalHours > 0 && remainMin > 0 -> "~${totalHours} ч ${remainMin} мин"
        totalHours > 0 -> "~${totalHours} ч"
        else -> "~${totalMinutes} мин"
    }

    // All ingredients as flat list for checkbox tracking
    val allIngredients = remember(recipe.id) {
        recipe.ingredients.entries.flatMap { entry ->
            entry.value.map { ingredient -> "${entry.key}|$ingredient" }
        }
    }

    // Checkbox state — reset when entering screen (keyed by recipe.id)
    val checkedIngredients = remember(recipe.id) {
        mutableStateListOf<String>()
    }

    // Count helpers (used for future total counter)
    @Suppress("unused") val totalIngredientCount = allIngredients.size
    @Suppress("unused") val checkedCount = checkedIngredients.size

    Surface(
        modifier = modifier.fillMaxSize(),
        color = BackgroundDark
    ) {
        // Root Box: content + sticky bottom button
        Box(modifier = Modifier.fillMaxSize()) {
            // Scrollable content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 88.dp) // space for sticky button
            ) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = TextSecondary
                        )
                    }
                    Text(
                        text = "${recipe.emoji} ${recipe.name}",
                        style = MaterialTheme.typography.titleLarge,
                        color = AccentGold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.width(48.dp))
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp)
                ) {
                    // Description
                    item {
                        Text(
                            text = recipe.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary
                        )
                    }

                    // Total time, steps, difficulty
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BackgroundCard),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                InfoChip(label = "Время", value = timeText)
                                InfoChip(label = "Шагов", value = "${recipe.timeline.size}")
                                if (recipe.difficulty > 0) {
                                    DifficultyChip(difficulty = recipe.difficulty)
                                }
                            }
                        }
                    }

                    // Ingredients by section with checkboxes
                    items(recipe.ingredients.entries.toList()) { entry ->
                        val sectionIngredients = entry.value
                        val sectionChecked = sectionIngredients.count { ing ->
                            checkedIngredients.contains("${entry.key}|$ing")
                        }

                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = entry.key.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextAccent,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "✓ $sectionChecked из ${sectionIngredients.size} есть",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sectionChecked == sectionIngredients.size && sectionIngredients.isNotEmpty())
                                        StatusCompleted
                                    else TextSecondary
                                )
                            }
                            sectionIngredients.forEach { ingredient ->
                                val key = "${entry.key}|$ingredient"
                                val checked = key in checkedIngredients

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Checkbox
                                    Box(
                                        modifier = Modifier.size(22.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Surface(
                                            onClick = {
                                                if (checked) {
                                                    checkedIngredients.remove(key)
                                                } else {
                                                    checkedIngredients.add(key)
                                                }
                                            },
                                            color = if (checked) AccentGold else DividerColor,
                                            shape = CircleShape,
                                            modifier = Modifier.size(22.dp)
                                        ) {
                                            if (checked) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = BackgroundDark,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Text(
                                        text = ingredient,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (checked) TextSecondary.copy(alpha = 0.6f) else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Sticky bottom button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(BackgroundDark)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Button(
                    onClick = onStartBaking,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentGold,
                        contentColor = BackgroundDark
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Начать выпечку ${recipe.emoji}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun DifficultyChip(difficulty: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 5 dots, filled up to difficulty level
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(5) { i ->
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < difficulty) AccentGold else DividerColor
                        )
                )
            }
        }
        Text(
            text = "Сложность",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
