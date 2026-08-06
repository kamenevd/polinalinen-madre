package com.polinalinen.madre.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.utils.PhotoStore

/**
 * Cycle 12: фотокарточку можно наконец рассмотреть.
 *
 * До этого снимок жил только внутри своей вклеенной рамки — 110–170dp по
 * высоте, обрезанный по Crop, состаренный цветовым фильтром. Ни увеличить,
 * ни увидеть целиком, ни разглядеть корку.
 *
 * Здесь он открывается на весь экран: без старения (смотрим фотографию, а не
 * страницу книги), целиком — ContentScale.Fit, с двумя пальцами на увеличение
 * и перетаскиванием. Тёплая бумага не прячет управление: закрыть можно
 * нижней кнопкой, системной кнопкой «назад» и тапом по фону.
 */
object PhotoZoom {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * Сдвиг не даёт утащить снимок в пустоту: на исходном масштабе он всегда
     * по центру, а увеличенный ходит в пределах наехавших за край полей.
     */
    fun clampOffset(offset: Float, scale: Float, viewportPx: Float): Float {
        val slack = viewportPx * (clampScale(scale) - 1f) / 2f
        if (slack <= 0f) return 0f
        return offset.coerceIn(-slack, slack)
    }
}

@Composable
fun FullscreenPhotoViewer(
    photoPath: String,
    onDismiss: () -> Unit,
    caption: String? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        // Диалог во весь экран: карточку смотрят целиком, а не в окошке.
        // Системная кнопка «назад» закрывает его сама (dismissOnBackPress).
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val colors = AppColors.current
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }
        var viewportWidth by remember { mutableFloatStateOf(0f) }
        var viewportHeight by remember { mutableFloatStateOf(0f) }

        Box(
            Modifier
                .fillMaxSize()
                .background(colors.paper),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = PhotoStore.resolve(LocalContext.current, photoPath),
                contentDescription = caption ?: "Фотокарточка во весь экран",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 24.dp)
                    .padding(bottom = 72.dp)
                    .pointerInput(Unit) {
                        viewportWidth = size.width.toFloat()
                        viewportHeight = size.height.toFloat()
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = PhotoZoom.clampScale(scale * zoom)
                            offsetX = PhotoZoom.clampOffset(offsetX + pan.x, scale, viewportWidth)
                            offsetY = PhotoZoom.clampOffset(offsetY + pan.y, scale, viewportHeight)
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    },
            )

            BookButton(
                label = "Закрыть",
                onClick = onDismiss,
                variant = BookButtonVariant.SECONDARY,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            )

            Text(
                caption ?: "два пальца — приблизить",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 78.dp, start = 24.dp, end = 24.dp)
                    .semantics { contentDescription = "" },
            )
        }
    }
}
