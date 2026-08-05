package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Surface
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
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.utils.PhotoStore

/** One local photo from either a feeding or a completed bake. */
data class GalleryPhoto(
    val id: String,
    val path: String,
    val timestampMillis: Long,
    val caption: String,
)

/** Offline-first archive; full-size decoding is deferred to the viewer. */
@Composable
fun PhotoGalleryScreen(
    photos: List<GalleryPhoto>,
    onBack: () -> Unit,
) {
    val colors = AppColors.current
    val context = LocalContext.current
    val ordered = remember(photos) {
        photos.asSequence()
            .filter { it.path.isNotBlank() }
            .sortedByDescending { it.timestampMillis }
            .toList()
    }
    var opened by remember { mutableStateOf<GalleryPhoto?>(null) }

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 132.dp),
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
                    BackLabel("Дневник закваски", onClick = onBack)
                    Text(
                        "ФОТО ЗАКВАСКИ",
                        color = colors.cocoa,
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
            if (ordered.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyGallery(colors)
                }
            } else {
                items(ordered, key = { it.id }) { photo ->
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
    val colors = AppColors.current
    val file = remember(photo.path) { PhotoStore.resolve(context, photo.path) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .drawBehind { drawRect(colors.cream) }
            .clickable(onClickLabel = "Открыть фото", onClick = onClick),
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
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun EmptyGallery(colors: com.polinalinen.madre.ui.theme.MadreExtendedColors) {
    Box(
        Modifier.fillMaxWidth().padding(top = 54.dp, bottom = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Фотографии появятся здесь после кормления\nили готовой выпечки.",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 16.sp,
        )
    }
}
