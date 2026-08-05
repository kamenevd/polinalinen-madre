package com.polinalinen.madre.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.polinalinen.madre.model.ChapterPhoto
import com.polinalinen.madre.model.ChapterPhotos
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.utils.PhotoStore

/** Всё, на что смотрят снаружи: тег страницы для тестов листания. */
object ChapterPhotoViewer {
    const val PAGER_TAG = "chapter-photo-pager"
}

/**
 * Cycle 14: фотографии ОДНОЙ главы во весь экран, с листанием влево-вправо.
 *
 * Раньше снимки главы жили в лайтбоксе-списке: их можно было прокрутить
 * вертикально в карточке 420dp высотой, но не рассмотреть. Теперь это
 * настоящий просмотр — и он намеренно ограничен своей главой: человек открыл
 * «Бородинский», значит листает бородинский, а не всю историю книги подряд.
 *
 * Открывается на последнем снимке (список приходит свежими вперёд), упирается
 * в границы главы, честно говорит про пропавший файл и закрывается кнопкой,
 * тапом мимо и системной «назад». Декодирует Coil и только текущую страницу —
 * галерея целиком в память не поднимается.
 */
// HorizontalPager в foundation 1.6 всё ещё помечен экспериментальным. Согласие
// осознанное и точечное: это единственный способ листать страницы жестом, не
// собирая свой скролл поверх Compose.
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChapterPhotoViewer(
    photos: List<ChapterPhoto>,
    chapterName: String,
    onDismiss: () -> Unit,
    // Cycle 14: страница приходит снаружи и уходит наружу. Внутри диалога её
    // негде сохранить так, чтобы она пережила поворот экрана: диалог живёт
    // ровно столько, сколько открыта глава, а сама глава — состояние Полки.
    initialPage: Int = 0,
    onPageChange: (Int) -> Unit = {},
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val colors = AppColors.current
        Box(Modifier.fillMaxSize().background(colors.paper)) {
            if (photos.isEmpty()) {
                // Досюда доходить не должны — плитка без фото не открывается,
                // — но пустой чёрный экран был бы неотличим от поломки.
                EmptyChapterPage(chapterName)
            } else {
                val pagerState = rememberPagerState(
                    initialPage = ChapterPhotos.clampIndex(initialPage, photos.size),
                ) { photos.size }
                // Наверх уходит только осевшая страница: пока палец тянет
                // снимок, «текущей» успевают побывать обе соседние.
                LaunchedEffect(pagerState, photos.size) {
                    snapshotFlow { pagerState.settledPage }
                        .collect { onPageChange(ChapterPhotos.clampIndex(it, photos.size)) }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().testTag(ChapterPhotoViewer.PAGER_TAG),
                ) { page ->
                    ChapterPhotoPage(photos[ChapterPhotos.clampIndex(page, photos.size)])
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val current = photos[ChapterPhotos.clampIndex(pagerState.currentPage, photos.size)]
                    Text(
                        ChapterPhotos.caption(current),
                        color = colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    val counter = ChapterPhotos.counterText(pagerState.currentPage, photos.size)
                    if (counter.isNotEmpty()) {
                        Text(
                            counter,
                            color = colors.crust,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    BookButton(
                        label = "Закрыть",
                        onClick = onDismiss,
                        variant = BookButtonVariant.SECONDARY,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterPhotoPage(photo: ChapterPhoto) {
    val colors = AppColors.current
    val context = LocalContext.current
    val file = remember(photo.path) { PhotoStore.resolve(context, photo.path) }
    val caption = ChapterPhotos.caption(photo)

    Box(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 24.dp).padding(bottom = 108.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (file.isFile) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(file).crossfade(false).build(),
                contentDescription = caption,
                // Fit, а не Crop: снимок смотрят целиком, без старящих фильтров —
                // это фотография, а не вклеенная в страницу карточка.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Файл могли удалить из галереи телефона, а запись в книге осталась.
            Column(
                Modifier.semantics { contentDescription = caption },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "файла больше нет",
                    color = colors.espresso,
                    fontFamily = FontFamily.Serif,
                    fontSize = 17.sp,
                )
                Text(
                    "снимок удалили с телефона — запись о выпечке осталась в книге",
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyChapterPage(chapterName: String) {
    val colors = AppColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "в главе «$chapterName» пока нет фотографий",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
    }
}
