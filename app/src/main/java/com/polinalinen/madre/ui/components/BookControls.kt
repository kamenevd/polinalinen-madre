package com.polinalinen.madre.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.theme.AppColors

/**
 * Cycle 12: одна кнопка на всю книгу.
 *
 * До этого каждый экран собирал свою CTA из Box + clickable + drawBehind, и
 * расходились они во всём сразу: высота выходила от 33 до 48dp, роль для
 * TalkBack не объявлялась нигде, а быстрый двойной тап проходил дважды —
 * «Дальше» перескакивал шаг, «Вписать в дневник» заводил два кормления.
 *
 * Здесь это закрыто в одном месте: мишень никогда не меньше 48dp, роль
 * объявлена, повторное нажатие в пределах окна [TapGate] игнорируется.
 * Внешность — прежняя книжная: скругление 4dp, заливка Espresso у главного
 * действия и рамка 1.5dp у второстепенного, никакого material-рельефа.
 */

/**
 * Защита от дребезга пальца и двойного тапа. Отдельный класс с явным «сейчас»
 * — чтобы правило проверялось юнит-тестом, а не наблюдением за живым экраном.
 */
class TapGate(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {

    private var lastAcceptedAt: Long? = null

    /** true — нажатие принято; false — оно пришло слишком быстро вслед за прошлым. */
    fun accept(nowMillis: Long): Boolean {
        val last = lastAcceptedAt
        if (last != null && nowMillis - last < windowMillis) return false
        lastAcceptedAt = nowMillis
        return true
    }

    companion object {
        /**
         * Полсекунды с запасом: человек не нажимает «Дальше» дважды осознанно
         * быстрее, а дребезг и случайный двойной тап укладываются в окно.
         */
        const val DEFAULT_WINDOW_MILLIS = 600L
    }
}

/** Минимальная мишень пальца — правило книги, а не только Material. */
val MinTouchTarget = 48.dp

enum class BookButtonVariant { PRIMARY, SECONDARY }

@Composable
fun BookButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BookButtonVariant = BookButtonVariant.PRIMARY,
    enabled: Boolean = true,
    caption: String? = null,
    repeatable: Boolean = false,
) {
    val colors = AppColors.current
    val gate = remember(repeatable) { if (repeatable) null else TapGate() }
    val primary = variant == BookButtonVariant.PRIMARY
    // Выключенная кнопка гасится, но остаётся читаемой: это страница книги,
    // а не серый прямоугольник.
    val ink = when {
        !enabled -> colors.flour
        primary -> colors.paper
        else -> colors.espresso
    }
    val frame = if (enabled) colors.espresso else colors.flour

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = MinTouchTarget)
                .clickable(
                    enabled = enabled,
                    onClickLabel = label,
                    role = Role.Button,
                ) {
                    val accepted = repeatable || gate?.accept(System.currentTimeMillis()) == true
                    if (accepted) onClick()
                }
                .drawBehind {
                    val radius = CornerRadius(4.dp.toPx())
                    if (primary) {
                        drawRoundRect(frame, cornerRadius = radius)
                    } else {
                        drawRoundRect(frame, cornerRadius = radius, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = ink,
                fontFamily = FontFamily.Serif,
                fontSize = 16.sp,
                letterSpacing = 1.sp,
                textAlign = TextAlign.Center,
            )
        }
        if (caption != null) {
            Text(
                caption,
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

/**
 * Тихое текстовое действие — «убрать», «заменить фотокарточку», «повторить».
 * Выглядит как приписка на полях, но мишень у него всё та же, 48dp: до
 * Cycle 12 это была строка 12sp высотой в два пальца ногтя.
 *
 * Cycle 18: приписка на полях — всё-таки действие, и отличать её от подписи
 * надо не по догадке. Кегль поднят до [TextActionSize] (наравне с основным
 * текстом страницы), а под словами идёт пунктир цветом Crust — рукописное
 * подчёркивание, а не material-ссылка. Двух признаков сразу — размера и
 * пунктира — хватает, чтобы «заглянуть ещё раз» не читалось продолжением
 * строки, над которой оно стоит.
 */
@Composable
fun TextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    val ink = if (enabled) colors.crust else colors.flour
    Box(
        modifier
            .then(bookAction(label = label, enabled = enabled, onClick = onClick))
            .padding(horizontal = 8.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = ink,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = TextActionSize,
            modifier = Modifier.drawBehind {
                val thickness = 1.dp.toPx()
                val y = size.height - thickness
                drawLine(
                    color = ink,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = thickness,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(3.dp.toPx(), 2.dp.toPx()),
                        0f,
                    ),
                )
            },
        )
    }
}

/**
 * Строка-крутилка: одно значение видно сразу, следующий тап ведёт к следующему.
 * repeatable=true — это осознанный быстрый круг, где гейт двойного тапа не нужен.
 */
@Composable
fun TapCycleRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color = AppColors.current.cocoa,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MinTouchTarget)
            .then(
                if (enabled) {
                    bookAction(
                        label = "$label: $value",
                        repeatable = true,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 15.sp)
        Text(
            value,
            color = valueColor,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 14.sp,
        )
    }
}

/** Кегль тихого действия — тот же, что у основного текста страницы. */
val TextActionSize = 15.sp

/**
 * Cycle 18: нажатие там, где кнопки с рамкой быть не может, — строка
 * оглавления, талон выпечки, загнутый уголок, фотокарточка.
 *
 * Голый `Modifier.clickable` на такой площади соблюдал ровно одно правило из
 * трёх: он работал. Роль для TalkBack не объявлялась, что случится — не
 * говорилось, а размер мишени выходил каким получится у содержимого. Здесь всё
 * три сведены в одно место, вместе с [TapGate]: двойной тап по строке
 * оглавления открывал главу дважды и клал второй разворот поверх первого.
 *
 * Ставится через `then`, чтобы порядок был виден на месте вызова:
 * `Modifier.fillMaxWidth().then(bookAction("Открыть главу") { … })`.
 */
@Composable
fun bookAction(
    label: String,
    enabled: Boolean = true,
    repeatable: Boolean = false,
    onClick: () -> Unit,
): Modifier {
    val gate = remember(repeatable) { if (repeatable) null else TapGate() }
    return Modifier
        .defaultMinSize(minHeight = MinTouchTarget)
        .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) {
            val accepted = repeatable || gate?.accept(System.currentTimeMillis()) == true
            if (accepted) onClick()
        }
}

/**
 * Ссылка «назад» в шапке страницы. Раньше это был PageLabel 10sp с clickable
 * прямо на тексте — мишень высотой около 14dp в самом верхнем углу экрана.
 */
@Composable
fun BackLabel(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.then(bookAction(label = "Назад: $text", onClick = onClick)),
        contentAlignment = Alignment.CenterStart,
    ) {
        PageLabel("← $text")
    }
}

/**
 * Спрос перед необратимым: «точно бросить выпечку?». Форма — страница книги
 * (скругление 4dp, бумага Cream), не material-попап.
 *
 * Подтверждающее действие стоит СПРАВА и отделено от «остаться» пробелом:
 * случайно попасть в него, промахнувшись мимо соседней кнопки, нельзя.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        containerColor = colors.cream,
        titleContentColor = colors.espresso,
        textContentColor = colors.espresso,
        title = {
            Text(title, fontFamily = FontFamily.Serif, fontSize = 20.sp)
        },
        text = {
            Text(
                message,
                fontFamily = FontFamily.Serif,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = colors.cocoa,
            )
        },
        confirmButton = {},
        dismissButton = {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookButton(
                    label = dismissLabel,
                    onClick = onDismiss,
                    variant = BookButtonVariant.SECONDARY,
                    modifier = Modifier.width(140.dp),
                )
                Spacer(Modifier.width(12.dp))
                BookButton(
                    label = confirmLabel,
                    onClick = onConfirm,
                    modifier = Modifier.width(140.dp),
                )
            }
        },
    )
}
