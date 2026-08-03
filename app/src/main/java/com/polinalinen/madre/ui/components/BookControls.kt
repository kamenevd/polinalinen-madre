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
) {
    val colors = AppColors.current
    val gate = remember { TapGate() }
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
                ) { if (gate.accept(System.currentTimeMillis())) onClick() }
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
 */
@Composable
fun TextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AppColors.current
    val gate = remember { TapGate() }
    Box(
        modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) {
                if (gate.accept(System.currentTimeMillis())) onClick()
            }
            .padding(horizontal = 8.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) colors.crust else colors.flour,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 13.sp,
        )
    }
}

/**
 * Ссылка «назад» в шапке страницы. Раньше это был PageLabel 10sp с clickable
 * прямо на тексте — мишень высотой около 14dp в самом верхнем углу экрана.
 */
@Composable
fun BackLabel(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .defaultMinSize(minHeight = MinTouchTarget)
            .clickable(onClickLabel = "Назад: $text", role = Role.Button) { onClick() },
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
