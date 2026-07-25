package com.polinalinen.madre.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.polinalinen.madre.ui.theme.AppColors

/**
 * QR-код гостевой страницы (DESIGN-V4.md Cycle 7, фича GuestPage). Модули —
 * Espresso («типографская краска») по бумаге подложки: рисуем BitMatrix сами,
 * без bitmap-а. Квадратные модули без скруглений — это функциональный узор,
 * а не декоративная плашка; контраст Espresso/Cream сканеру достаточен.
 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, qrSize: Dp = 168.dp) {
    val ink = AppColors.current.espresso
    // MARGIN=0 + запрошенный размер 1×1: ZXing возвращает минимальную матрицу
    // ровно в модулях QR, тихую зону даёт padding подложки.
    val matrix = remember(content) {
        QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier.size(qrSize)) {
        val modules = matrix.width
        val cell = size.minDimension / modules
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = ink,
                        topLeft = Offset(x * cell, y * cell),
                        // +0.5px перекрытие — против волосяных щелей от округления.
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}
