package com.polinalinen.madre.ui.photo

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.utils.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Стол оформления» (Cycle 11): полноэкранный локальный редактор, который
 * встаёт между выбором кадра и вклейкой в книгу. До него снимок приклеивался
 * молча и сразу — теперь сначала видно, как карточка ляжет на страницу.
 *
 * Всё рисование локальное ([PhotoDecorRenderer]): бумажная рамка, тёплый свет
 * и один штамп-оттиск в выбранном углу. Ни сети, ни генерации, ни текста на
 * самом снимке.
 *
 * Три выхода:
 *  - «Готово» — сохраняет оформленный JPEG в filesDir и отдаёт абсолютный путь;
 *  - «Без оформления» — сохраняет исходный кадр (только развёрнутый по EXIF);
 *  - «Отмена» / системная «назад» — не вклеивает ничего, временный файл чистит
 *    вызывающая сторона (см. ui/photo/PhotoAttachment).
 */
@Composable
fun PhotoDesignerDialog(
    staged: File,
    kind: PhotoStore.PhotoKind,
    key: Long,
    onCancel: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val colors = AppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val palette = remember(colors) {
        PhotoDecorRenderer.Palette(
            paper = colors.paper.toArgb(),
            cream = colors.cream.toArgb(),
            ink = colors.espresso.toArgb(),
            accent = colors.cocoa.toArgb(),
        )
    }

    var decor by remember { mutableStateOf(PhotoDecor()) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // Превью — тот же самый рендер, что уйдёт в файл, только с меньшей стороной:
    // так «Готово» не может дать картинку, отличную от увиденной.
    LaunchedEffect(staged) {
        val decoded = withContext(Dispatchers.IO) { PhotoStore.decodeUpright(staged, PREVIEW_EDGE_PX) }
        source = decoded
        unreadable = decoded == null
    }
    LaunchedEffect(source, decor) {
        val bitmap = source ?: return@LaunchedEffect
        preview = withContext(Dispatchers.Default) {
            PhotoDecorRenderer.render(bitmap, decor, palette).asImageBitmap()
        }
    }

    fun finish(withDecor: PhotoDecor?) {
        if (saving) return
        saving = true
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                if (withDecor == null) {
                    PhotoStore.commit(context, staged, kind, key)
                } else {
                    val full = PhotoStore.decodeUpright(staged, PhotoStore.MAX_EDGE_PX)
                    if (full == null) null else {
                        val rendered = PhotoDecorRenderer.render(full, withDecor, palette)
                        val saved = PhotoStore.commitBitmap(context, rendered, kind, key)
                        full.recycle()
                        rendered.recycle()
                        saved
                    }
                }
            }
            saving = false
            if (path != null) onSaved(path) else onCancel()
        }
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.heightIn(min = 48.dp).clickable { onCancel() }.padding(vertical = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        PageLabel("← Отмена")
                    }
                    PageLabel("Стол оформления")
                }

                Text(
                    "как ляжет карточка",
                    color = colors.espresso,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )
                Text(
                    "рамка, тёплый свет и один оттиск — всё рисуется здесь же, на столе",
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                )

                Box(
                    Modifier
                        .padding(horizontal = 22.dp, vertical = 14.dp)
                        .fillMaxWidth()
                        .height(280.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val shown = preview
                    when {
                        shown != null -> Image(
                            bitmap = shown,
                            contentDescription = "Фотокарточка, как она ляжет в книгу",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        unreadable -> Text(
                            "этот кадр не читается — попробуйте другой",
                            color = colors.terracotta,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                        else -> Text(
                            "проявляется…",
                            color = colors.cocoa,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 13.sp,
                        )
                    }
                }

                HairRule(Modifier.padding(horizontal = 22.dp))

                DecorGroup("Рамка") {
                    PhotoFrame.entries.forEach { frame ->
                        DecorChip(
                            label = frame.label,
                            selected = decor.frame == frame,
                            onClick = { decor = decor.withFrame(frame) },
                        )
                    }
                }

                DecorGroup("Тёплый свет") {
                    DecorChip(
                        label = "прогреть",
                        selected = decor.warm,
                        onClick = { if (!decor.warm) decor = decor.toggleWarm() },
                    )
                    DecorChip(
                        label = "как снято",
                        selected = !decor.warm,
                        onClick = { if (decor.warm) decor = decor.toggleWarm() },
                    )
                }

                DecorGroup("Оттиск") {
                    PhotoStamp.entries.forEach { stamp ->
                        DecorChip(
                            label = stamp.label,
                            selected = decor.stamp == stamp,
                            onClick = { decor = decor.withStamp(stamp) },
                        )
                    }
                }
                Text(
                    if (decor.stamp == null) "оттиск не обязателен — можно оставить карточку чистой"
                    else "тап по выбранному оттиску снимает его",
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )

                if (decor.stamp != null) {
                    DecorGroup("Угол") {
                        StampCorner.entries.forEach { corner ->
                            DecorChip(
                                label = corner.label,
                                selected = decor.stampCorner == corner,
                                onClick = { decor = decor.withCorner(corner) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Box(
                    Modifier
                        .padding(horizontal = 22.dp)
                        .fillMaxWidth()
                        .clickable(enabled = source != null && !saving) { finish(decor) }
                        .drawBehind { drawRoundRect(colors.espresso, cornerRadius = CornerRadius(4.dp.toPx())) }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (saving) "вклеиваем…" else "Готово",
                        color = colors.paper,
                        fontFamily = FontFamily.Serif,
                        fontSize = 16.sp,
                        letterSpacing = 1.sp,
                    )
                }

                Box(
                    Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .fillMaxWidth()
                        .clickable(enabled = !saving) { finish(null) }
                        .drawBehind {
                            drawRoundRect(
                                colors.espresso,
                                cornerRadius = CornerRadius(4.dp.toPx()),
                                style = Stroke(1.5.dp.toPx()),
                            )
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Без оформления",
                        color = colors.espresso,
                        fontFamily = FontFamily.Serif,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

/** Превью держим мелким: рендер идёт на каждый тап по кнопке оформления. */
private const val PREVIEW_EDGE_PX = 900

/**
 * Ряд переключателей одного свойства. FlowRow, а не Row: подписи русские и
 * длинные («сверху слева»), в одну строку на узком экране они не встают.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecorGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp)) {
        PageLabel(title, color = AppColors.current.espresso)
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

/** Штамп-переключатель из «Живой книги»: рамка 1.5dp, скругление 4dp, без заливки-пилюли. */
@Composable
private fun DecorChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clickable { onClick() }
            .drawBehind {
                if (selected) {
                    drawRoundRect(colors.espresso, cornerRadius = CornerRadius(4.dp.toPx()))
                } else {
                    drawRoundRect(
                        colors.cocoa,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) colors.paper else colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}
