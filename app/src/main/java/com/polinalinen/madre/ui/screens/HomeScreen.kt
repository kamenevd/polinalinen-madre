package com.polinalinen.madre.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.ui.components.DogEar
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.RibbonBookmark
import com.polinalinen.madre.ui.components.Stamp
import com.polinalinen.madre.ui.components.TicketFrame
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.viewmodel.BakingViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главная — «Первая полоса» (DESIGN-V4.md, экран 1).
 * Masthead → строка от Мадре → талон «В ПЕЧИ» + ляссе → оглавление.
 */
@Composable
fun HomeScreen(
    madreHeadline: String,
    favoriteIds: Set<String>,
    onToggleFavorite: (String) -> Unit,
    onOpenRecipe: (String) -> Unit,
    onOpenStarter: () -> Unit,
    onOpenTimer: (sessionId: Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenShelf: () -> Unit,
    viewModel: BakingViewModel = viewModel(),
) {
    val colors = AppColors.current
    val recipes by viewModel.recipes.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val remaining by viewModel.remainingSeconds.collectAsState()
    // Ляссе ведёт к той выпечке, что ближе всего к следующему шагу.
    val nearestSessionId = sessions.minByOrNull { remaining[it.id] ?: Long.MAX_VALUE }?.id

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Box {
            LazyColumn(modifier = Modifier.statusBarsPadding()) {
                item { Masthead(onOpenSettings, onOpenShelf) }
                item { MadreLine(madreHeadline, onOpenStarter) }
                // Талон на каждую активную выпечку разом — печей в доме может
                // готовиться несколько одновременно (2026-07-21).
                items(sessions, key = { it.id }) { s ->
                    Box(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
                        TicketFrame(Modifier.fillMaxWidth().clickable { onOpenTimer(s.id) }) {
                            ActiveBakingTicket(
                                recipeName = s.recipe.name,
                                stepIndex = s.currentStepIndex,
                                stepCount = s.recipe.timeline.size,
                                portions = s.scaleFactor.toInt().coerceAtLeast(1),
                                isPaused = s.isPaused,
                            )
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        PageLabel("Оглавление", color = colors.espresso)
                        Text(
                            "все ${recipes.size}",
                            color = colors.crust,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 12.sp,
                        )
                    }
                }
                items(recipes, key = { it.id }) { recipe ->
                    ChapterRow(
                        index = recipes.indexOf(recipe) + 1,
                        recipe = recipe,
                        isFavorite = recipe.id in favoriteIds,
                        onClick = { onOpenRecipe(recipe.id) },
                        onToggleFavorite = { onToggleFavorite(recipe.id) },
                    )
                }
                item { Colophon() }
            }
            // Ляссе поверх страницы — только пока идёт хотя бы одна выпечка
            if (nearestSessionId != null) {
                RibbonBookmark(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 34.dp)
                        .clickable { onOpenTimer(nearestSessionId) }
                )
            }
        }
    }
}

@Composable
private fun Masthead(onOpenSettings: () -> Unit, onOpenShelf: () -> Unit) {
    val colors = AppColors.current
    val date = SimpleDateFormat("EEEE · d MMMM", Locale("ru")).format(Date())
    Column(
        Modifier.fillMaxWidth().padding(top = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PageLabel("Домашняя пекарня Полины", modifier = Modifier.clickable { onOpenShelf() })
        Text(
            "МАДРЕ",
            color = colors.espresso,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 44.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 4.dp).clickable { onOpenSettings() },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeavyRule(Modifier.weight(1f))
            Text(
                date.uppercase(),
                color = colors.cocoa,
                fontFamily = FontFamily.SansSerif,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            HeavyRule(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MadreLine(headline: String, onOpenStarter: () -> Unit) {
    val colors = AppColors.current
    Column(Modifier.fillMaxWidth().clickable { onOpenStarter() }.padding(horizontal = 22.dp, vertical = 6.dp)) {
        PageLabel("Мадре пишет")
        Text(
            "«$headline»",
            color = colors.espresso,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 18.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ActiveBakingTicket(
    recipeName: String,
    stepIndex: Int,
    stepCount: Int,
    portions: Int,
    isPaused: Boolean,
) {
    val colors = AppColors.current
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(recipeName, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 17.sp)
                Text(
                    "  ×$portions ${familyWord(portions)}",
                    color = colors.crust,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                )
            }
            Stamp("В печи", colors.terracotta)
        }
        Text(
            "шаг ${stepIndex + 1} из $stepCount" + if (isPaused) " · пауза" else "",
            color = colors.cocoa,
            fontFamily = FontFamily.SansSerif,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(stepCount) { i ->
                val tone = when {
                    i < stepIndex -> colors.espresso
                    i == stepIndex -> colors.crust
                    else -> colors.flour
                }
                Box(Modifier.weight(1f).height(3.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(tone) }
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

@Composable
private fun ChapterRow(
    index: Int,
    recipe: Recipe,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val colors = AppColors.current
    val difficultyLabel = when {
        recipe.difficulty <= 2 -> "легко" to colors.sage
        recipe.difficulty == 3 -> "средне" to colors.crust
        else -> "сложно" to colors.terracotta
    }
    val hours = recipe.timeline.sumOf { it.durationMinutes } / 60
    Box(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "%02d".format(index),
                color = colors.flour,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                modifier = Modifier.width(40.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(recipe.name, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 16.sp)
                Row {
                    Text(
                        "$hours ч · ",
                        color = colors.cocoa, fontFamily = FontFamily.SansSerif, fontSize = 11.sp,
                    )
                    Text(
                        difficultyLabel.first,
                        color = difficultyLabel.second, fontFamily = FontFamily.SansSerif, fontSize = 11.sp,
                    )
                }
            }
        }
        // Загнутый уголок — избранное (механика #3). Touch target 48dp через padding.
        DogEar(
            isFavorite = isFavorite,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable { onToggleFavorite() }
                .padding(6.dp),
        )
        HairRule(Modifier.align(Alignment.BottomCenter).padding(horizontal = 22.dp))
    }
}

@Composable
private fun Colophon() {
    val colors = AppColors.current
    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HeavyRule()
        Text(
            "— тираж: одна семья · печатается с любовью —",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(8.dp))
    }
}
