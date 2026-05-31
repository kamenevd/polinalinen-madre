package com.polinalinen.madre.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/**
 * Full-screen photo gallery with swipe navigation, share, and close gestures.
 *
 * @param photos   List of absolute file paths to photos
 * @param initialIndex  Starting photo index (default 0)
 * @param onClose  Callback when gallery is dismissed
 */
@Composable
fun FullScreenGallery(
    photos: List<String>,
    initialIndex: Int = 0,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)),
        pageCount = { photos.size }
    )
    val currentPage = pagerState.currentPage

    // Dismiss threshold for swipe-down
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = 200f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .pointerInput(Unit) {
                detectVerticalDrag { _, dragAmount ->
                    dragOffset += dragAmount
                    if (dragOffset > dismissThreshold) {
                        onClose()
                    } else if (dragOffset < -dismissThreshold) {
                        onClose()
                    }
                }
            }
    ) {
        // Close button (top-right)
        Text(
            text = "✕",
            color = Color.White,
            fontSize = 24.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .statusBarsPadding()
                .clickable(onClick = onClose)
                .size(40.dp)
                .wrapContentSize(Alignment.Center)
        )

        // Photo counter (top-left)
        Text(
            text = "${currentPage + 1} / ${photos.size}",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 16.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .statusBarsPadding()
        )

        // Horizontal pager for photos
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 80.dp),
            verticalAlignment = Alignment.CenterVertically
        ) { page ->
            val photoPath = photos[page]
            val bitmap = remember(photoPath) {
                BitmapFactory.decodeFile(photoPath)?.asImageBitmap()
            }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Фото ${page + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp)
                )
            }
        }

        // Bottom bar: dots indicator + share button
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Dot indicators
            if (photos.size > 1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    repeat(photos.size) { index ->
                        Box(
                            modifier = Modifier
                                .size(if (index == currentPage) 8.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == currentPage) Color.White
                                    else Color.White.copy(alpha = 0.4f)
                                )
                        )
                    }
                }
            }

            // Share button
            val context = LocalContext.current
            OutlinedButton(
                onClick = {
                    sharePhoto(context, photos[currentPage])
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White
                ),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Поделиться",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Поделиться", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Share a photo via Android share sheet
 */
private fun sharePhoto(context: Context, photoPath: String) {
    val file = File(photoPath)
    if (!file.exists()) return

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(Intent.createChooser(shareIntent, "Поделиться фото"))
}
