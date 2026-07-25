package com.polinalinen.madre.ui.components

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.model.Season
import com.polinalinen.madre.model.SeasonalEdition
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.ui.theme.Caramel
import com.polinalinen.madre.ui.theme.Cocoa
import com.polinalinen.madre.ui.theme.Cool
import com.polinalinen.madre.ui.theme.Cream
import com.polinalinen.madre.ui.theme.Crust
import com.polinalinen.madre.ui.theme.Sage
import com.polinalinen.madre.ui.theme.SageLight
import com.polinalinen.madre.ui.theme.Terracotta
import java.util.Calendar

/**
 * DESIGN-V4.md Cycle 8, фича «Гербарий» (Herbarium). Читатель вкладывает между
 * страниц рецепта цветок или листик — набор зависит от сезона (Season из
 * SeasonalEdition, Cycle 3). Со временем находка высыхает: цвет уходит к
 * гербарным бурым тонам, а на странице проступает бледный отпечаток.
 *
 * Хранение — SharedPreferences (madre_prefs), ключ herbarium_<recipeId>,
 * значение «specimenId|placedAtMillis». Логика — чистые функции (юнит-тест
 * HerbariumTest), рисование — Canvas в HerbariumSection ниже.
 */
object Herbarium {
    /** Через месяц между страниц любая находка высыхает полностью. */
    const val DRY_AFTER_DAYS = 30

    enum class Kind { FLOWER, LEAF, SPRIG, CLUSTER }

    /**
     * [label] — именительный падеж (подпись под находкой), [actionLabel] —
     * винительный («вложить ромашку»), [petals] — лепестки цветка или
     * веточки-иголочки у SPRIG, [toneIndex] — пара свежий/сухой цвет.
     */
    data class Specimen(
        val id: String,
        val label: String,
        val actionLabel: String,
        val kind: Kind,
        val petals: Int,
        val toneIndex: Int,
    )

    private val ALL = listOf(
        Specimen("spruce", "еловая веточка", "еловую веточку", Kind.SPRIG, 8, toneIndex = 0),
        Specimen("rowan", "гроздь рябины", "гроздь рябины", Kind.CLUSTER, 7, toneIndex = 3),
        Specimen("cherry", "цветок вишни", "цветок вишни", Kind.FLOWER, 5, toneIndex = 4),
        Specimen("birch", "лист берёзы", "лист берёзы", Kind.LEAF, 0, toneIndex = 1),
        Specimen("chamomile", "ромашка", "ромашку", Kind.FLOWER, 9, toneIndex = 4),
        Specimen("cornflower", "василёк", "василёк", Kind.FLOWER, 8, toneIndex = 5),
        Specimen("maple", "кленовый лист", "кленовый лист", Kind.LEAF, 0, toneIndex = 2),
        Specimen("wheat", "колосок", "колосок", Kind.SPRIG, 10, toneIndex = 2),
    )

    private val BY_SEASON = mapOf(
        Season.WINTER to listOf("spruce", "rowan"),
        Season.SPRING to listOf("cherry", "birch"),
        Season.SUMMER to listOf("chamomile", "cornflower"),
        Season.AUTUMN to listOf("maple", "wheat"),
    )

    /** Что можно найти за окном в этот сезон и вложить в книгу. */
    fun specimensFor(season: Season): List<Specimen> =
        BY_SEASON.getValue(season).map { id -> ALL.first { it.id == id } }

    fun specimenById(id: String): Specimen? = ALL.find { it.id == id }

    /** 0 — только что сорван, 1 — полностью высох (через [DRY_AFTER_DAYS]). */
    fun dryness(placedAtMillis: Long, nowMillis: Long): Float =
        if (placedAtMillis <= 0L || nowMillis <= placedAtMillis) 0f
        else ((nowMillis - placedAtMillis).toFloat() / (DRY_AFTER_DAYS * 86_400_000L))
            .coerceIn(0f, 1f)

    /** Отпечаток на странице: у свежей находки его нет, у сухой — бледный след. */
    fun imprintAlpha(dryness: Float): Float = (dryness * 0.12f).coerceIn(0f, 0.12f)

    /** Подпись под находкой — состояние словами, без цифр. */
    fun caption(dryness: Float): String = when {
        dryness < 0.15f -> "вложено недавно"
        dryness < 1f -> "подсыхает между страниц"
        else -> "высох и оставил след на странице"
    }

    // ── Хранение «specimenId|placedAtMillis» ─────────────────────────────

    fun record(specimenId: String, placedAtMillis: Long): String = "$specimenId|$placedAtMillis"

    /** null на пустую строку, мусор и неизвестный образец — секция просто молчит. */
    fun parse(raw: String?): Pair<Specimen, Long>? {
        val parts = raw?.split("|") ?: return null
        if (parts.size != 2) return null
        val specimen = specimenById(parts[0]) ?: return null
        val millis = parts[1].toLongOrNull() ?: return null
        return specimen to millis
    }
}

/**
 * «Гербарий» на развороте рецепта. Пусто — предлагает вложить одну из двух
 * сезонных находок; вложено — рисует её Canvas-ом поверх бледного отпечатка,
 * цвет тянется к сухому по мере dryness. «Вынуть из книги» очищает запись —
 * отпечаток исчезает вместе с находкой: страница бумажная, но добрая.
 */
@Composable
fun HerbariumSection(recipeId: String, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("madre_prefs", Context.MODE_PRIVATE) }
    val key = "herbarium_$recipeId"
    var placed by remember(recipeId) { mutableStateOf(Herbarium.parse(prefs.getString(key, null))) }

    val season = remember { SeasonalEdition.seasonForMonth(Calendar.getInstance().get(Calendar.MONTH) + 1) }
    val options = remember(season) { Herbarium.specimensFor(season) }

    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HairRule(Modifier.weight(1f))
            PageLabel("Гербарий", color = colors.espresso, modifier = Modifier.padding(horizontal = 10.dp))
            HairRule(Modifier.weight(1f))
        }

        val current = placed
        if (current == null) {
            Text(
                "что нашлось за окном — можно вложить между страниц",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                options.forEach { specimen ->
                    Text(
                        "вложить ${specimen.actionLabel}",
                        color = colors.crust,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .clickable {
                                val now = System.currentTimeMillis()
                                prefs.edit().putString(key, Herbarium.record(specimen.id, now)).apply()
                                placed = specimen to now
                            },
                    )
                }
            }
        } else {
            val (specimen, placedAt) = current
            val dryness = Herbarium.dryness(placedAt, System.currentTimeMillis())
            Canvas(Modifier.fillMaxWidth().height(110.dp).padding(top = 8.dp)) {
                drawSpecimen(specimen, dryness)
            }
            Text(
                "${specimen.label} — ${Herbarium.caption(dryness)}",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Text(
                "вынуть из книги",
                color = colors.crust,
                fontFamily = FontFamily.SansSerif,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable {
                        prefs.edit().remove(key).apply()
                        placed = null
                    },
            )
        }
    }
}

// Пары свежий/сухой: хвоя, молодая зелень, осенняя охра, ягоды, белые лепестки,
// васильковый Cool. Сухой цвет всегда тянется к гербарным бурым (Cocoa/Caramel).
private val SpecimenTones = listOf(
    Sage to Cocoa,
    SageLight to Caramel,
    Crust to Cocoa,
    Terracotta to Cocoa,
    Cream to Caramel,
    Cool to Cocoa,
)

private fun specimenColor(toneIndex: Int, dryness: Float): Color {
    val (fresh, dried) = SpecimenTones[toneIndex]
    return Color(
        red = fresh.red + (dried.red - fresh.red) * dryness,
        green = fresh.green + (dried.green - fresh.green) * dryness,
        blue = fresh.blue + (dried.blue - fresh.blue) * dryness,
        alpha = 1f,
    )
}

/**
 * Находка лежит в центре блока с лёгким наклоном, под ней — отпечаток: тот же
 * силуэт, чуть сдвинутый и бледный (alpha растёт с dryness). Сухая находка
 * ещё и слегка бледнеет сама — краски выцветают.
 */
private fun DrawScope.drawSpecimen(specimen: Herbarium.Specimen, dryness: Float) {
    val color = specimenColor(specimen.toneIndex, dryness)
    val bodyAlpha = 1f - dryness * 0.25f
    val imprint = Herbarium.imprintAlpha(dryness)
    val center = Offset(size.width / 2, size.height / 2)

    rotate(-8f, pivot = center) {
        if (imprint > 0f) {
            translate(left = 7.dp.toPx(), top = 5.dp.toPx()) {
                drawSpecimenShape(specimen, Cocoa.copy(alpha = imprint), center)
            }
        }
        drawSpecimenShape(specimen, color.copy(alpha = bodyAlpha), center)
    }
}

private fun DrawScope.drawSpecimenShape(specimen: Herbarium.Specimen, color: Color, center: Offset) {
    when (specimen.kind) {
        Herbarium.Kind.FLOWER -> {
            val petalLen = 26.dp.toPx()
            val petalW = 9.dp.toPx()
            repeat(specimen.petals) { i ->
                rotate(360f * i / specimen.petals, pivot = center) {
                    drawOval(
                        color = color,
                        topLeft = Offset(center.x - petalW / 2, center.y - petalLen - 4.dp.toPx()),
                        size = Size(petalW, petalLen),
                    )
                }
            }
            // Серединка — тёплая, как у полевых цветов; у сухого темнеет.
            drawCircle(Caramel, 6.dp.toPx(), center)
        }
        Herbarium.Kind.LEAF -> {
            val w = 34.dp.toPx()
            val h = 52.dp.toPx()
            drawOval(color, Offset(center.x - w / 2, center.y - h / 2), Size(w, h))
            // Черешок и центральная жилка одной линией, боковые — короче.
            drawLine(
                Cocoa.copy(alpha = color.alpha * 0.5f),
                start = Offset(center.x, center.y - h / 2 + 4.dp.toPx()),
                end = Offset(center.x, center.y + h / 2 + 10.dp.toPx()),
                strokeWidth = 1.2.dp.toPx(),
            )
            repeat(3) { i ->
                val y = center.y - h / 4 + i * h / 4
                drawLine(
                    Cocoa.copy(alpha = color.alpha * 0.35f),
                    start = Offset(center.x, y),
                    end = Offset(center.x + w / 3, y - 6.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    Cocoa.copy(alpha = color.alpha * 0.35f),
                    start = Offset(center.x, y),
                    end = Offset(center.x - w / 3, y - 6.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        Herbarium.Kind.SPRIG -> {
            val len = 64.dp.toPx()
            val top = Offset(center.x, center.y - len / 2)
            drawLine(
                color,
                start = top,
                end = Offset(center.x, center.y + len / 2),
                strokeWidth = 2.dp.toPx(),
            )
            repeat(specimen.petals) { i ->
                val y = top.y + len * (i + 1) / (specimen.petals + 1)
                val side = if (i % 2 == 0) 1f else -1f
                drawLine(
                    color,
                    start = Offset(center.x, y),
                    end = Offset(center.x + side * 14.dp.toPx(), y - 8.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }
        Herbarium.Kind.CLUSTER -> {
            // Гроздь: короткая ножка и ягоды кружком под ней.
            drawLine(
                Cocoa.copy(alpha = color.alpha * 0.6f),
                start = Offset(center.x, center.y - 30.dp.toPx()),
                end = Offset(center.x, center.y - 8.dp.toPx()),
                strokeWidth = 1.5.dp.toPx(),
            )
            repeat(specimen.petals) { i ->
                val angle = Math.toRadians(360.0 * i / specimen.petals)
                val r = 12.dp.toPx()
                val cx = center.x + (r * Math.cos(angle)).toFloat()
                val cy = center.y + 6.dp.toPx() + (r * 0.7f * Math.sin(angle)).toFloat()
                drawCircle(color, 5.dp.toPx(), Offset(cx, cy))
            }
            drawCircle(color, 5.dp.toPx(), Offset(center.x, center.y + 6.dp.toPx()))
        }
    }
}
