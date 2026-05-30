package com.polinalinen.madre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.TasteReview
import com.polinalinen.madre.model.ServingTip
import com.polinalinen.madre.ui.theme.AccentBrown
import com.polinalinen.madre.ui.theme.AccentCream
import com.polinalinen.madre.ui.theme.AccentGold
import com.polinalinen.madre.ui.theme.AccentRose
import com.polinalinen.madre.ui.theme.BackgroundCard
import com.polinalinen.madre.ui.theme.BackgroundCardHover
import com.polinalinen.madre.ui.theme.BackgroundDark
import com.polinalinen.madre.ui.theme.DividerColor
import com.polinalinen.madre.ui.theme.DifficultyEasy
import com.polinalinen.madre.ui.theme.DifficultyMedium
import com.polinalinen.madre.ui.theme.DifficultyHard
import com.polinalinen.madre.ui.theme.StatusCompleted
import com.polinalinen.madre.ui.theme.TextAccent
import com.polinalinen.madre.ui.theme.TextPrimary
import com.polinalinen.madre.ui.theme.TextSecondary

@Composable
fun RecipeDetailScreen(
    recipe: Recipe,
    onStartBaking: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val checkedIngredients = remember { mutableStateListOf<String>() }
    val allIngredients = recipe.ingredients.values.flatten()
    val totalMinutes = recipe.timeline.sumOf { it.durationMinutes }
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val timeText = if (hours > 0) {
        if (minutes > 0) "${hours}ч ${minutes}мин" else "${hours}ч"
    } else {
        "${minutes}мин"
    }
    val stepCount = recipe.timeline.size
    val difficultyLabel = when (recipe.difficulty) {
        1 -> "Легко"
        2 -> "Просто"
        3 -> "Средне"
        else -> ""
    }

    Surface(color = BackgroundDark) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 96.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // ── 1. Hero section ──
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFFD4B88C),
                                        BackgroundCard,
                                        Color(0xFFC9A87C)
                                    )
                                )
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            BackgroundDark.copy(alpha = 0.9f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        BackgroundDark.copy(alpha = 0.9f)
                                    )
                                )
                            )
                        )
                        Text(
                            text = recipe.emoji,
                            fontSize = 72.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 8.dp)
                                .padding(top = 8.dp)
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Назад",
                                    tint = AccentBrown
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = recipe.name,
                                color = AccentBrown,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (recipe.tasteReviews.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 16.dp, bottom = 12.dp)
                                    .background(
                                        color = AccentGold.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "💬 ${recipe.tasteReviews.size}",
                                    color = AccentBrown,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // ── 2. Description + Meta chips (fixed height) ──
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp)
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = recipe.description,
                            color = TextSecondary,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        // 3 chips — fixed height 72dp each
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            MetaChip(
                                icon = "⏱",
                                label = timeText,
                                modifier = Modifier.weight(1f)
                            )
                            MetaChip(
                                icon = "📝",
                                label = "$stepCount шагов",
                                modifier = Modifier.weight(1f)
                            )
                            MetaChip(
                                icon = "📊",
                                label = difficultyLabel,
                                iconColor = when (recipe.difficulty) {
                                    1 -> DifficultyEasy
                                    2 -> DifficultyMedium
                                    3 -> DifficultyHard
                                    else -> TextSecondary
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }

                // ── 3. Ingredients with checkboxes ──
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp)
                    ) {
                        Text(
                            text = "📋 Ингредиенты",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val checkedCount = allIngredients.count { it in checkedIngredients }
                        Text(
                            text = "✓ $checkedCount из ${allIngredients.size} есть",
                            color = if (checkedCount == allIngredients.size && allIngredients.isNotEmpty()) {
                                StatusCompleted
                            } else {
                                TextSecondary
                            },
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
                recipe.ingredients.forEach { (sectionName, items) ->
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp)
                        ) {
                            Text(
                                text = sectionName,
                                color = TextAccent,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    items(items) { ingredient ->
                        val isChecked = ingredient in checkedIngredients
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        checkedIngredients.remove(ingredient)
                                    } else {
                                        checkedIngredients.add(ingredient)
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        color = if (isChecked) StatusCompleted else Color.Transparent
                                    )
                                    .border(
                                        width = if (isChecked) 0.dp else 1.5.dp,
                                        color = if (isChecked) StatusCompleted else DividerColor,
                                        shape = RoundedCornerShape(5.dp)
                                    )
                            ) {
                                if (isChecked) {
                                    Text(
                                        text = "✓",
                                        color = BackgroundDark,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = ingredient,
                                color = if (isChecked) TextSecondary else TextPrimary,
                                fontSize = 15.sp
                            )
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── 4. Serving tips ──
                if (recipe.servingTips.isNotEmpty()) {
                    item {
                        ServingTipsCard(
                            title = "✨ Как подать",
                            tips = recipe.servingTips,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // ── 5. Variations ──
                if (recipe.variations.isNotEmpty()) {
                    item {
                        ServingTipsCard(
                            title = "🔄 Вариации",
                            tips = recipe.variations,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // ── 6. Taste reviews ──
                if (recipe.tasteReviews.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp)
                        ) {
                            Text(
                                text = "💬 Как это на вкус",
                                color = TextPrimary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                    items(recipe.tasteReviews) { review ->
                        TasteReviewCard(
                            review = review,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }

            // ── Sticky CTA ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                BackgroundDark,
                                BackgroundDark
                            )
                        )
                    )
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp, top = 32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(color = AccentBrown)
                        .clickable { onStartBaking() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Всё есть. Начинаем ${recipe.emoji}",
                        color = AccentCream,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun MetaChip(
    icon: String,
    label: String,
    iconColor: Color = TextPrimary,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BackgroundCard),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(vertical = 10.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = icon,
                fontSize = 20.sp,
                color = iconColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun TasteReviewCard(
    review: TasteReview,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BackgroundCard),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color = BackgroundCardHover)
                ) {
                    Text(
                        text = review.avatarEmoji,
                        fontSize = 18.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = review.author,
                        color = TextAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    val stars = buildString {
                        for (i in 1..5) {
                            append(if (i <= review.rating) "★" else "☆")
                        }
                    }
                    Text(
                        text = stars,
                        color = AccentGold,
                        fontSize = 14.sp
                    )
                }
            }
            if (review.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = review.text,
                    color = TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
fun ServingTipsCard(
    title: String,
    tips: List<ServingTip>,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = AccentGold.copy(alpha = 0.08f)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            tips.forEachIndexed { index, tip ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = tip.emoji,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = tip.text,
                        color = TextSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                }
                if (index < tips.lastIndex) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}
