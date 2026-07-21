package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.viewmodel.BakingViewModel
import java.time.Instant
import java.time.ZoneId

/**
 * Уведомления — «Записки на полях» (DESIGN-V4.md, экран 7). Мокап ещё не
 * согласован. Настоящей системы push-уведомлений в приложении пока нет
 * (WorkManager/Receiver сознательно отложены — см. AndroidManifest.xml,
 * "Отложено сознательно"), поэтому вместо списка настроек-переключателей
 * это read-only лента заметок на полях, собранная из того, что уже реально
 * происходит на кухне: фаза закваски, активные выпечки, недавняя история.
 * Когда появится реальный Worker — эта же лента станет источником текста
 * для push, а не наоборот.
 */
@Composable
fun NotificationsScreen(
    phase: GrowthPhase,
    starterHeadline: String,
    recentBakes: List<BakeRecordEntity>,
    onBack: () -> Unit,
    onOpenStarter: () -> Unit,
    onOpenTimer: (sessionId: Long) -> Unit,
    viewModel: BakingViewModel = viewModel(),
) {
    val colors = AppColors.current
    val sessions by viewModel.sessions.collectAsState()

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Touch target 48dp — сам ярлык мельче, раздуваем hit-area паддингом.
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable { onBack() }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    PageLabel("← Первая полоса")
                }
            }

            Text(
                "Записки на полях",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
            Text(
                "то, что происходит на кухне сейчас",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 2.dp),
            )

            val hasAny = sessions.isNotEmpty() || recentBakes.isNotEmpty()
            Column(
                Modifier
                    .padding(horizontal = 22.dp, vertical = 18.dp)
                    .drawBehind {
                        drawRect(colors.flour, size = Size(2.dp.toPx(), size.height))
                    }
                    .padding(start = 14.dp)
            ) {
                val starterTone = when (phase) {
                    GrowthPhase.PEAK -> colors.sage
                    GrowthPhase.HUNGRY -> colors.terracotta
                    else -> colors.cocoa
                }
                MarginNote(
                    label = "дневник закваски",
                    text = "«$starterHeadline»",
                    tone = starterTone,
                    onClick = onOpenStarter,
                )

                sessions.forEach { s ->
                    MarginNote(
                        label = "в печи",
                        text = "«${s.recipe.name}» — шаг ${s.currentStepIndex + 1} из ${s.recipe.timeline.size}",
                        tone = colors.terracotta,
                        onClick = { onOpenTimer(s.id) },
                    )
                }

                recentBakes.take(5).forEach { record ->
                    MarginNote(
                        label = romanDate(record.completedAtMillis),
                        text = "испекли «${record.recipeName}», ×${record.portions}",
                        tone = colors.sage,
                        onClick = null,
                    )
                }

                if (!hasAny) {
                    Text(
                        "здесь пока тихо — записки появятся, когда что-то происходит на кухне",
                        color = colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarginNote(
    label: String,
    text: String,
    tone: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)?,
) {
    val colors = AppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.heightIn(min = 48.dp).clickable(onClick = onClick) else it }
            .padding(vertical = 7.dp),
        verticalAlignment = if (onClick != null) Alignment.CenterVertically else Alignment.Top,
    ) {
        Text(
            label,
            color = tone,
            fontFamily = FontFamily.SansSerif,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.width(84.dp).padding(top = 2.dp),
        )
        Text(
            text,
            color = colors.espresso,
            fontFamily = FontFamily.Cursive,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        )
    }
}

/** «20.VII» — как в StarterDiaryScreen/BakingCompleteScreen; тут же локальный дубль. */
private fun romanDate(millis: Long): String {
    val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${date.dayOfMonth}.${roman[date.monthValue - 1]}"
}
