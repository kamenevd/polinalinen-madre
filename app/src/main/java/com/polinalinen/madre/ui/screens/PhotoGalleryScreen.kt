package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.ui.kit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.FullscreenPhotoViewer
import com.polinalinen.madre.utils.PhotoStore
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation provenance, assigned by the persisted-row producers, never inferred from text. */
enum class GalleryPhotoSource { STARTER, BAKING }

/** A local reference; legacy/unclassified callers honestly belong to All only. */
data class GalleryPhoto(
    val id: String,
    val path: String,
    val timestampMillis: Long,
    val caption: String,
    val source: GalleryPhotoSource? = null,
)

/** Offline-first archive; full-size decoding is deferred to the viewer. */
@Composable
fun PhotoGalleryScreen(
    photos: List<GalleryPhoto>,
    onBack: () -> Unit,
) {
    val colors = LocalPaper.current
    val context = LocalContext.current
    val ordered = remember(photos) {
        photos.asSequence()
            .filter { it.path.isNotBlank() }
            .sortedByDescending { it.timestampMillis }
            .toList()
    }
    var selected by rememberSaveable { mutableStateOf(0) }
    val sources = listOf(null, GalleryPhotoSource.STARTER, GalleryPhotoSource.BAKING)
    // Namespace headers separately; repeated producer ids/paths remain separate references.
    val keyed = remember(ordered) {
        val occurrences = mutableMapOf<String, Int>()
        ordered.map { photo ->
            val occurrence = occurrences.getOrDefault(photo.id, 0)
            occurrences[photo.id] = occurrence + 1
            photo to "gallery-record:${photo.id}:$occurrence"
        }
    }
    val zone = ZoneId.systemDefault()
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL uuuu", Locale.forLanguageTag("ru")) }
    val visible = keyed.filter { selected == 0 || it.first.source == sources[selected] }
    val months = remember(visible, zone) {
        visible.groupBy { YearMonth.from(Instant.ofEpochMilli(it.first.timestampMillis).atZone(zone)) }
            .toSortedMap(reverseOrder())
    }
    val labels = listOf("Все ${ordered.size}",
        "Закваска ${ordered.count { it.source == GalleryPhotoSource.STARTER }}",
        "Выпечка ${ordered.count { it.source == GalleryPhotoSource.BAKING }}")
    var opened by remember { mutableStateOf<GalleryPhoto?>(null) }
    val snackbar = remember { PaperSnackbarState() }
    val noticeScope = rememberCoroutineScope()
    var noticeJob by remember { mutableStateOf<Job?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { noticeJob?.cancel() }

    PaperSurface(color = colors.bg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 132.dp),
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        BackLabel("Дневник закваски", onClick = onBack)
                        Text("Фото", color = colors.ink, style = PaperType.h1,
                            modifier = Modifier.fillMaxWidth())
                        PaperSegmented(labels, selected, { selected = it }, label = "Источник фото")
                    }
                }
                if (visible.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (ordered.isEmpty()) EmptyGallery(colors)
                        else PaperEmpty("Пока нет фото", "В этом разделе пока нет фотографий.",
                            Modifier.padding(top = 26.dp, bottom = 80.dp))
                    }
                } else {
                    months.forEach { (month, entries) ->
                        stickyHeader(key = "gallery-month:$month") {
                            SmallLabel(month.format(monthFormat), Modifier
                                .testTag("gallery-month-$month")
                                .fillMaxWidth()
                                .background(colors.bg)
                                .padding(vertical = 10.dp))
                        }
                        items(entries, key = { it.second }) { (photo, _) ->
                            GalleryTile(
                                photo = photo,
                                context = context,
                                onClick = {
                                    if (PhotoStore.isReadable(context, photo.path)) {
                                        noticeJob?.cancel()
                                        opened = photo
                                    } else if (noticeJob?.isActive != true) {
                                        // Coalesce repeated missing taps instead of queuing messages.
                                        noticeJob = noticeScope.launch { snackbar.showSnackbar("Файл не найден") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            PaperSnackbar(snackbar, Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp), fillMessageWidth = true)
        }
    }

    opened?.let { photo ->
        FullscreenPhotoViewer(
            photoPath = photo.path,
            caption = photo.caption,
            onDismiss = { opened = null },
        )
    }
}

@Composable
private fun GalleryTile(
    photo: GalleryPhoto,
    context: android.content.Context,
    onClick: () -> Unit,
) {
    val colors = LocalPaper.current
    val file = remember(photo.path) { PhotoStore.resolve(context, photo.path) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .testTag("gallery-photo-${photo.id}")
            .drawBehind { drawRect(colors.card) }
            .then(com.polinalinen.madre.ui.components.bookAction("Открыть фото", onClick = onClick)),
        contentAlignment = Alignment.Center,
    ) {
        if (file.isFile) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(file)
                    .size(320)
                    .crossfade(false)
                    .build(),
                contentDescription = photo.caption,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        } else {
            Text(
                "файл\nне найден",
                color = colors.ink2,style = PaperType.body,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun EmptyGallery(colors: com.polinalinen.madre.ui.kit.PaperColors) {
    PaperEmpty("Фото закваски", "Фотографии появятся здесь после кормления\nили готовой выпечки.",
        Modifier.padding(top = 26.dp, bottom = 80.dp))
}
