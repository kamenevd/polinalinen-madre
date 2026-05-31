package com.polinalinen.madre.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.TasteReview
import com.polinalinen.madre.model.ServingTip
import com.polinalinen.madre.ui.theme.AppColors

private fun lookupHeroResId(recipeId: String, packageName: String, resources: android.content.res.Resources): Int {
    val heroMap = mapOf(
        "pirozhki" to "hero_pirozhki",
        "belyashi" to "hero_belyashi",
        "ciabatta" to "hero_ciabatta",
        "focaccia" to "hero_focaccia",
        "pizza" to "hero_pizza",
        "waffles" to "hero_waffles",
        "pancakes" to "hero_pancakes",
        "home_bread" to "hero_home_bread",
        "family_bread" to "hero_family_bread",
        "cinnamon_buns" to "hero_cinnamon_buns",
        "garlic_buns" to "hero_garlic_buns"
    )
    val resName = heroMap[recipeId] ?: return 0
    return resources.getIdentifier(resName, "drawable", packageName)
}

@Composable
fun RecipeDetailScreen(
    recipe: Recipe,
    onStartBaking: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val heroResId = remember(recipe.id) {
        lookupHeroResId(recipe.id, context.packageName, context.resources)
    }
    val galleryPhotos = remember(recipe.id) {
        mutableStateOf(loadPhotosForRecipe(context, recipe.id))
    }
    var showGallery by remember { mutableStateOf(false) }
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
        else -> "—"
    }
    val bgColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onBgColor = MaterialTheme.colorScheme.onBackground
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(color = bgColor) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Full-screen gallery overlay
            if (showGallery && galleryPhotos.value.isNotEmpty()) {
                FullScreenGallery(
                    photos = galleryPhotos.value,
                    initialIndex = 0,
                    onClose = { showGallery = false }
                )
            } else {
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
                                        surfaceColor,
                                        Color(0xFFC9A87C)
                                    )
                                )
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            bgColor.copy(alpha = 0.9f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        bgColor.copy(alpha = 0.9f)
                                    )
                                )
                            )
                        )
                        // Hero image or emoji fallback
                        if (heroResId != 0) {
                            Image(
                                painter = painterResource(id = heroResId),
                                contentDescription = recipe.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .align(Alignment.TopCenter)
                            )
                        } else {
                            Text(
                                text = recipe.emoji,
                                fontSize = 72.sp,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
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
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Назад",
                                    tint = AppColors.accentBrown
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = recipe.name,
                                color = AppColors.accentBrown,
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
                                        color = AppColors.accentGold.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "💬 ${recipe.tasteReviews.size}",
                                    color = AppColors.accentBrown,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // ── Gallery badge ──
                if (galleryPhotos.value.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp)
                        ) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = AppColors.accentGold.copy(alpha = 0.1f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { showGallery = true }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📸",
                                        fontSize = 20.sp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Ваши результаты",
                                            color = onBgColor,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "${galleryPhotos.value.size} фото",
                                            color = onSurfaceVariant,
                                            fontSize = 13.sp
                                        )
                                    }
                                    // Preview thumbnails
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.width(120.dp)
                                    ) {
                                        items(galleryPhotos.value.take(3)) { photoPath ->
                                            val bitmap = remember(photoPath) {
                                                BitmapFactory.decodeFile(photoPath)?.asImageBitmap()
                                            }
                                            bitmap?.let {
                                                Image(
                                                    bitmap = it,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(40.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                )
                                            }
                                        }
                                    }
                                }
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
                            color = onSurfaceVariant,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
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
                                label = "$stepCount ${pluralSteps(stepCount)}",
                                modifier = Modifier.weight(1f)
                            )
                            MetaChip(
                                icon = "📊",
                                label = difficultyLabel,
                                iconColor = when (recipe.difficulty) {
                                    1 -> AppColors.difficultyEasy
                                    2 -> AppColors.difficultyMedium
                                    3 -> AppColors.difficultyHard
                                    else -> onSurfaceVariant
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
                            color = onBgColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        val checkedCount = allIngredients.count { it in checkedIngredients }
                        Text(
                            text = "✓ $checkedCount из ${allIngredients.size} есть",
                            color = if (checkedCount == allIngredients.size && allIngredients.isNotEmpty()) {
                                AppColors.statusCompleted
                            } else {
                                onSurfaceVariant
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
                                color = AppColors.accentCream,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    items(items) { ingredient ->
                        val uniqueKey = "$sectionName::$ingredient"
                        val isChecked = uniqueKey in checkedIngredients
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        checkedIngredients.remove(uniqueKey)
                                    } else {
                                        checkedIngredients.add(uniqueKey)
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
                                        color = if (isChecked) AppColors.statusCompleted else Color.Transparent
                                    )
                                    .border(
                                        width = if (isChecked) 0.dp else 1.5.dp,
                                        color = if (isChecked) AppColors.statusCompleted else AppColors.dividerColor,
                                        shape = RoundedCornerShape(5.dp)
                                    )
                            ) {
                                if (isChecked) {
                                    Text(
                                        text = "✓",
                                        color = bgColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = ingredient,
                                color = if (isChecked) onSurfaceVariant else onBgColor,
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
                                color = onBgColor,
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
                                bgColor,
                                bgColor
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
                        .background(color = AppColors.accentBrown)
                        .clickable { onStartBaking() }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Всё есть. Начинаем ${recipe.emoji}",
                        color = AppColors.accentCream,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            } // else (not showGallery)
        }
    }
}

@Composable
fun MetaChip(
    icon: String,
    label: String,
    iconColor: Color = MaterialTheme.colorScheme.onBackground,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                color = MaterialTheme.colorScheme.onBackground,
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                        .background(color = MaterialTheme.colorScheme.surfaceVariant)
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
                        color = AppColors.accentCream,
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
                        color = AppColors.accentGold,
                        fontSize = 14.sp
                    )
                }
            }
            if (review.text.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = review.text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            containerColor = AppColors.accentGold.copy(alpha = 0.08f)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
