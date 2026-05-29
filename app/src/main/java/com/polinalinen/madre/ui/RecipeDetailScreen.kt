package com.polinalinen.madre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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

                    // Total time
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
                            }
                        }
                    }

                    // Ingredients by section
                    items(recipe.ingredients.entries.toList()) { entry ->
                        Column {
                            Text(
                                text = entry.key.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.titleMedium,
                                color = TextAccent,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            entry.value.forEach { ingredient ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = AccentGold,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Text(
                                        text = ingredient,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary
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
