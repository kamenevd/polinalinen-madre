package com.polinalinen.madre.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.theme.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private fun galleryDir(context: Context): File {
    val dir = File(context.filesDir, "bake_gallery")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun savePhoto(context: Context, uri: Uri): String {
    val fileName = "bake_${UUID.randomUUID()}.jpg"
    val outFile = File(galleryDir(context), fileName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(outFile).use { output ->
            input.copyTo(output)
        }
    }
    return fileName
}

private fun loadPhotos(context: Context): List<String> {
    val dir = galleryDir(context)
    return dir.listFiles()
        ?.filter { it.name.startsWith("bake_") && it.name.endsWith(".jpg") }
        ?.sortedByDescending { it.lastModified() }
        ?.map { it.absolutePath }
        ?: emptyList()
}

@Composable
fun CompletedScreen(
    onHome: () -> Unit,
    onRestart: () -> Unit = {},
    recipeEmoji: String = "🍞",
    recipeName: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var galleryPhotos by remember { mutableStateOf(loadPhotos(context)) }

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            savePhoto(context, it)
            galleryPhotos = loadPhotos(context)
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = BackgroundDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = recipeEmoji,
                style = MaterialTheme.typography.displayLarge
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Готово!",
                style = MaterialTheme.typography.displayLarge,
                color = AccentGold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (recipeName.isNotBlank()) "$recipeName готов(а)!\nПриятного аппетита! 🍞" else "Ваша выпечка готова.\nПриятного аппетита! 🍞",
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Photo gallery section
            if (galleryPhotos.isNotEmpty()) {
                Text(
                    text = "📸 Ваши результаты",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(galleryPhotos.take(10)) { photoPath ->
                        val bitmap = remember(photoPath) {
                            BitmapFactory.decodeFile(photoPath)?.asImageBitmap()
                        }
                        bitmap?.let {
                            Image(
                                bitmap = it,
                                contentDescription = "Результат выпечки",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(1.dp, AccentGold.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Add photo button
            OutlinedButton(
                onClick = { photoLauncher.launch("image/*") },
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentGold.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AccentGold
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "📸 Добавить фото результата",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onHome,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentGold,
                    contentColor = BackgroundDark
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "На главную",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onRestart,
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentGold),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AccentGold
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = "🔄 Испечь ещё раз",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
