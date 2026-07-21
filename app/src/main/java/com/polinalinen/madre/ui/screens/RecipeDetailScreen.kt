package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.RecipeScaler
import com.polinalinen.madre.ui.components.DottedLeaderRow
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.utils.heroResFor
import com.polinalinen.madre.viewmodel.BakingViewModel

/**
 * Рецепт — «Разворот» (DESIGN-V4.md, экран 2).
 * РЕЦЕПТ № NN → название → подвал-статистика → «НА СКОЛЬКО ПЕЧЁМ» →
 * фотокарточка → ингредиенты с отточиями → CTA.
 * Текст ингредиентов/шагов — строго из recipes.json (PDF Полины).
 */
@Composable
fun RecipeDetailScreen(
    recipeId: String,
    onBack: () -> Unit,
    onStartBaking: (sessionId: Long) -> Unit,
    viewModel: BakingViewModel = viewModel(),
) {
    val colors = AppColors.current
    val recipes by viewModel.recipes.collectAsState()
    val recipe = recipes.find { it.id == recipeId } ?: return
    val chapterIndex = recipes.indexOf(recipe) + 1

    var portions by remember { mutableIntStateOf(1) }
    val scaleFactor = portions.toDouble()

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PageLabel("← Оглавление", Modifier.clickable { onBack() })
                PageLabel("Рецепт № %02d".format(chapterIndex))
            }

            Text(
                recipe.name,
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            Text(
                recipe.description,
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )

            StatsFooter(recipe)

            PortionSelector(
                portions = portions,
                onSelect = { portions = it },
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
            )

            PastedPhoto(recipe, Modifier.padding(horizontal = 22.dp, vertical = 10.dp))

            recipe.ingredients.forEach { (section, items) ->
                val sectionTitle = when (section) {
                    "sponge" -> "Опара"
                    "main" -> "Тесто"
                    else -> section
                }
                PageLabel(sectionTitle, Modifier.padding(start = 22.dp, top = 14.dp), color = colors.espresso)
                Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
                    items.forEach { ingredient ->
                        val text = RecipeScaler.scaledDisplayText(ingredient, scaleFactor)
                        // Отточие: имя слева, граммы справа. Если формат нераздельный — одной строкой.
                        val parts = text.split(" г ", limit = 2)
                        if (parts.size == 2 && parts[0].toDoubleOrNull() != null) {
                            DottedLeaderRow(name = parts[1], value = "${parts[0]} г")
                        } else {
                            Text(
                                text,
                                color = colors.espresso,
                                fontFamily = FontFamily.Serif,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .padding(horizontal = 22.dp)
                    .fillMaxWidth()
                    .clickable {
                        val sessionId = viewModel.startBaking(recipe, scaleFactor)
                        onStartBaking(sessionId)
                    }
                    .drawBehind {
                        drawRoundRect(colors.espresso, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Начать выпечку",
                    color = colors.paper,
                    fontFamily = FontFamily.Serif,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp,
                )
            }
            Text(
                "таймер поведёт за руку, шаг за шагом",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            // Просьба Полины (2026-07-21, помнить и не убирать): весь рецепт должен
            // быть написан целиком, книжным текстом, прямо на этой странице —
            // для тех, кто печёт по памяти рецепта, а не по шагам таймера.
            // Источник текста — recipe.timeline, тот же, что видит таймер: так
            // книжная версия не может разойтись с шагами или показать не те цифры.
            FullRecipeSection(recipe, Modifier.padding(top = 32.dp))
        }
    }
}

@Composable
private fun FullRecipeSection(recipe: Recipe, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HairRule(Modifier.weight(1f))
            PageLabel("Рецепт целиком", color = colors.espresso, modifier = Modifier.padding(horizontal = 10.dp))
            HairRule(Modifier.weight(1f))
        }
        Text(
            "для тех, кто печёт по-своему — без таймера, по книге",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 8.dp),
        )
        Column(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
            recipe.timeline.forEachIndexed { i, step ->
                Row(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                    Text(
                        "${i + 1}",
                        color = colors.flour,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 26.sp,
                        modifier = Modifier.width(34.dp),
                    )
                    Column {
                        Text(
                            step.title.uppercase(),
                            color = colors.crust,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            step.description,
                            color = colors.espresso,
                            fontFamily = FontFamily.Serif,
                            fontSize = 15.5.sp,
                            lineHeight = 23.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
        Text(
            "— вот и весь рецепт —",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 17.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StatsFooter(recipe: Recipe) {
    val colors = AppColors.current
    val hours = recipe.timeline.sumOf { it.durationMinutes } / 60
    val roman = listOf("—", "I", "II", "III", "IV", "V")
    val difficultyColor = when {
        recipe.difficulty <= 2 -> colors.sage
        recipe.difficulty == 3 -> colors.crust
        else -> colors.terracotta
    }
    Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
        HeavyRule()
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            StatCell("${hours}ч", "время", Modifier.weight(1f))
            StatCell("${recipe.timeline.size}", "шагов", Modifier.weight(1f))
            StatCell(roman.getOrElse(recipe.difficulty) { "—" }, "сложность", Modifier.weight(1f), valueColor = difficultyColor)
        }
        HairRule()
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = AppColors.current.espresso,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            label.uppercase(),
            color = AppColors.current.cocoa,
            fontFamily = FontFamily.SansSerif,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

/**
 * «НА СКОЛЬКО ПЕЧЁМ» — pill-паттерн: активная ячейка залита Espresso и шире
 * остальных («×3 семьи»). Единственное место применения референса Yandex Music.
 */
@Composable
private fun PortionSelector(portions: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Column(modifier) {
        PageLabel("На сколько печём", color = colors.espresso)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .drawBehind {
                    drawRoundRect(
                        colors.espresso,
                        style = Stroke(1.5.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    )
                }
        ) {
            (1..5).forEach { n ->
                val active = n == portions
                Box(
                    Modifier
                        .weight(if (active) 1.5f else 1f)
                        .clickable { onSelect(n) }
                        .drawBehind { if (active) drawRect(colors.espresso) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (active) "×$n ${familyWord(n)}" else "×$n",
                        color = if (active) colors.paper else colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun familyWord(n: Int) = when (n) {
    1 -> "семья"
    in 2..4 -> "семьи"
    else -> "семей"
}

/** Hero-фото как вклеенная фотокарточка: белая рамка, лёгкий поворот. */
@Composable
private fun PastedPhoto(recipe: Recipe, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    val context = LocalContext.current
    val resId = heroResFor(context, recipe.id) ?: return
    Box(
        modifier
            .fillMaxWidth()
            .rotate(-1.2f)
            .drawBehind { drawRect(colors.cream) }
            .padding(8.dp)
    ) {
        Image(
            painter = rememberAsyncImagePainter(resId),
            contentDescription = "Фото: ${recipe.name}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(170.dp),
        )
    }
}
