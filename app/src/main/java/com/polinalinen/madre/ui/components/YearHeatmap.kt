package com.polinalinen.madre.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.model.YearGrid
import com.polinalinen.madre.model.YearRhythm
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.ui.theme.MadreExtendedColors

/**
 * Cycle 15: ритм года — 52 недельные колонки, листается вбок.
 *
 * Год не влезает в ширину телефона и не должен: сжать 364 клетки до экрана —
 * значит сделать каждую в две точки шириной. Поэтому колонки едут в LazyRow, а
 * открывается карта на сегодняшней неделе, а не на прошлогоднем январе, —
 * человек пришёл смотреть, как у него дела сейчас.
 *
 * Палитра тёплая (карамель → корочка → тёмный янтарь), а не зелёная с GitHub:
 * книга бумажная, и зелёный в ней не живёт (DESIGN-V4, «Warm Paper»).
 */
object YearHeatmapTags {
    const val WEEKS = "year-heatmap-weeks"
}

@Composable
fun YearHeatmap(grid: YearGrid, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    val labels = remember(grid) { YearRhythm.monthLabels(grid) }
    val state = rememberLazyListState()

    // Год отматывается к сегодняшнему дню один раз при появлении: дальше это
    // руки человека, и возвращать его к «сегодня» на каждой рекомпозиции —
    // значит отбирать у него карту.
    LaunchedEffect(grid.weeks.size) {
        if (grid.weeks.isNotEmpty()) state.scrollToItem(grid.weeks.lastIndex)
    }

    Column(modifier.fillMaxWidth()) {
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().testTag(YearHeatmapTags.WEEKS),
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
        ) {
            itemsIndexed(grid.weeks, key = { index, _ -> index }) { index, week ->
                WeekColumn(week = week, label = labels.getOrNull(index), colors = colors)
            }
        }
        Legend(colors, Modifier.padding(horizontal = 22.dp).padding(top = 10.dp))
    }
}

/** Одна неделя: подпись месяца сверху, под ней семь дней сверху вниз. */
@Composable
private fun WeekColumn(
    week: List<com.polinalinen.madre.model.YearDay?>,
    label: String?,
    colors: MadreExtendedColors,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        // Место под подпись занято всегда, даже когда подписи нет: иначе
        // колонки с названием месяца стояли бы ниже соседних.
        Box(Modifier.height(LABEL_HEIGHT).width(CELL_SIZE)) {
            if (label != null) {
                Text(
                    label,
                    color = colors.cocoa,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 8.sp,
                    // Подпись шире колонки и не должна её растягивать — она
                    // просто висит над своей неделей и заезжает на соседнюю.
                    modifier = Modifier.width(MONTH_LABEL_WIDTH),
                )
            }
        }
        week.forEach { day ->
            if (day == null) {
                // День, до которого книга ещё не дошла: пустое место, а не
                // клетка «здесь ничего не было».
                Spacer(Modifier.size(CELL_SIZE))
            } else {
                Box(
                    Modifier
                        .size(CELL_SIZE)
                        .clip(RoundedCornerShape(CELL_CORNER))
                        .drawBehind { drawRect(heatTone(YearRhythm.intensity(day.events), colors)) }
                        .semantics {
                            contentDescription = if (day.events > 0) {
                                "${day.date}: ${day.events}"
                            } else {
                                "${day.date}: пусто"
                            }
                        },
                )
            }
        }
    }
}

/** «меньше — больше»: без легенды тон клетки не значит ничего. */
@Composable
private fun Legend(colors: MadreExtendedColors, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "меньше",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 10.sp,
        )
        (0..YearRhythm.LEVELS).forEach { level ->
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(CELL_SIZE)
                    .clip(RoundedCornerShape(CELL_CORNER))
                    .drawBehind { drawRect(heatTone(level, colors)) },
            )
        }
        Text(
            "больше",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 10.sp,
        )
    }
}

/** Тёплая шкала: пергамент → карамель → корочка → тёмный янтарь. */
private fun heatTone(level: Int, colors: MadreExtendedColors): Color = when (level) {
    0 -> colors.parchment
    1 -> colors.caramel
    2 -> colors.crust
    else -> colors.amberDeep
}

private val CELL_SIZE = 12.dp
private val CELL_GAP = 3.dp

/** Углы почти прямые — бумага, не пластик (DESIGN-V4: максимум 4dp). */
private val CELL_CORNER = 2.dp

private val LABEL_HEIGHT = 11.dp
private val MONTH_LABEL_WIDTH = 28.dp
