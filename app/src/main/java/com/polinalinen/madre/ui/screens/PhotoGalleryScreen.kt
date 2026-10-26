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
    val visible = ordered.filter { selected == 0 || it.source == sources[selected] }
    val labels = listOf("Все ${ordered.size}",
        "Закваска ${ordered.count { it.source == GalleryPhotoSource.STARTER }}",
        "Выпечка ${ordered.count { it.source == GalleryPhotoSource.BAKING }}")
    var opened by remember { mutableStateOf<GalleryPhoto?>(null) }

    PaperSurface(color = colors.bg, modifier = Modifier.fillMaxSize()) {
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
                items(visible, key = { it.id }) { photo ->
                    GalleryTile(
                        photo = photo,
                        context = context,
                        onClick = { if (PhotoStore.isReadable(context, photo.path)) opened = photo },
                    )
                }
            }
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
