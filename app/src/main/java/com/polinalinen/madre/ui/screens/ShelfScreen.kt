package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.theme.AppColors

/**
 * Полка — твоя книга (2026-07-20/21). Профиль на устройстве всегда один;
 * друзья появятся здесь, когда книгой поделятся, а это уже требует своего
 * сервера — сейчас его нет (Дима, 2026-07-21: «пока без бэка»). До тех пор
 * показываем честную заглушку вместо того, чтобы изображать рабочую фичу.
 */
@Composable
fun ShelfScreen(
    myName: String,
    onBack: () -> Unit,
    onOpenMyBook: () -> Unit,
) {
    val colors = AppColors.current
    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding()) {
            PageLabel(
                "← Первая полоса",
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp).clickable { onBack() },
            )
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text("Полка", color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 26.sp)
                Text(
                    "твоя книга — и книги друзей, которые ею поделятся",
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 28.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Spine(
                    label = myName.ifBlank { "вы" },
                    color = colors.amberDeep,
                    outlined = true,
                    onClick = onOpenMyBook,
                )
                Spacer(Modifier.width(10.dp))
                DashedAddSpine()
            }

            Text(
                "делиться книгой можно будет, когда заработает сервер синхронизации — эта часть впереди",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )
        }
    }
}

@Composable
private fun Spine(label: String, color: androidx.compose.ui.graphics.Color, outlined: Boolean, onClick: () -> Unit) {
    val ink = AppColors.current.espresso
    Box(
        Modifier
            .width(40.dp)
            .height(130.dp)
            .clickable { onClick() }
            .drawBehind {
                drawRect(color)
                // Обводка — «это твоя книга», как в мокапе (outline на своём корешке).
                if (outlined) drawRect(ink, style = Stroke(width = 2.dp.toPx()))
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            label,
            color = androidx.compose.ui.graphics.Color(0xFFFAF6F0),
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun DashedAddSpine() {
    val colors = AppColors.current
    Box(
        Modifier
            .width(40.dp)
            .height(90.dp)
            .drawBehind {
                drawRect(
                    color = colors.flour,
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("+", color = colors.cocoa, fontSize = 20.sp)
    }
}
