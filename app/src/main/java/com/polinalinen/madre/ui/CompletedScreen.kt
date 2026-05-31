package com.polinalinen.madre.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.core.content.FileProvider
import com.polinalinen.madre.ui.theme.AppColors
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

fun galleryDir(context: Context): File {
    val dir = File(context.filesDir, "bake_gallery")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

fun savePhotoFromUri(context: Context, uri: Uri): String {
    val fileName = "bake_${UUID.randomUUID()}.jpg"
    val outFile = File(galleryDir(context), fileName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(outFile).use { output ->
            input.copyTo(output)
        }
    }
    return fileName
}

fun loadPhotosForRecipe(context: Context, recipeId: String): List<String> {
    val dir = galleryDir(context)
    return dir.listFiles()
        ?.filter { it.name.startsWith("bake_${recipeId}_") && it.name.endsWith(".jpg") }
        ?.sortedByDescending { it.lastModified() }
        ?.map { it.absolutePath }
        ?: emptyList()
}

fun loadAllPhotos(context: Context): List<String> {
    val dir = galleryDir(context)
    return dir.listFiles()
        ?.filter { it.name.startsWith("bake_") && it.name.endsWith(".jpg") }
        ?.sortedByDescending { it.lastModified() }
        ?.map { it.absolutePath }
        ?: emptyList()
}

fun saveCameraPhoto(context: Context, uri: Uri, recipeId: String): String {
    val fileName = "bake_${recipeId}_${UUID.randomUUID()}.jpg"
    val outFile = File(galleryDir(context), fileName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(outFile).use { output ->
            input.copyTo(output)
        }
    }
    return fileName
}

@Composable
fun CompletedScreen(
    onHome: () -> Unit,
    recipeEmoji: String = "🍞",
    recipeName: String = "",
    recipeId: String = "",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var galleryPhotos by remember { mutableStateOf(loadPhotosForRecipe(context, recipeId)) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var showFullScreenGallery by remember { mutableStateOf(false) }
    var showPermissionRationale by remember { mutableStateOf(false) }
    var pendingCameraLaunch by remember { mutableStateOf(false) }

    // Camera result launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success && cameraImageUri != null) {
            saveCameraPhoto(context, cameraImageUri!!, recipeId)
            try { File(cameraImageUri!!.path!!).delete() } catch (_: Exception) {}
            galleryPhotos = loadPhotosForRecipe(context, recipeId)
        }
        pendingCameraLaunch = false
    }

    // Permission launcher — requests Camera + Write External Storage
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        if (cameraGranted && pendingCameraLaunch) {
            // Permissions granted — launch camera
            val uri = cameraImageUri
            if (uri != null) {
                cameraLauncher.launch(uri)
            }
        } else {
            showPermissionRationale = true
            pendingCameraLaunch = false
        }
    }

    // Full-screen gallery overlay
    if (showFullScreenGallery && galleryPhotos.isNotEmpty()) {
        FullScreenGallery(
            photos = galleryPhotos,
            initialIndex = 0,
            onClose = { showFullScreenGallery = false }
        )
        return
    }

    // Permission denied dialog with link to Settings
    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text("Нужна камера") },
            text = { Text("Нужна камера для фото результата выпечки") },
            confirmButton = {
                TextButton(onClick = {
                    showPermissionRationale = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Настройки")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationale = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
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
                color = AppColors.accentGold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (recipeName.isNotBlank()) "$recipeName готов(а)!\nПриятного аппетита! 🍞" else "Ваша выпечка готова.\nПриятного аппетита! 🍞",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Photo gallery section
            if (galleryPhotos.isNotEmpty()) {
                Text(
                    text = "📸 Ваши результаты",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
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
                                    .border(1.dp, AppColors.accentGold.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                    .clickable {
                                        showFullScreenGallery = true
                                    }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Camera button — requests permissions on first tap, then launches camera
            OutlinedButton(
                onClick = {
                    val photoFile = File(context.cacheDir, "temp_camera_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        photoFile
                    )
                    cameraImageUri = uri
                    pendingCameraLaunch = true
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                },
                border = BorderStroke(1.dp, AppColors.accentGold.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AppColors.accentGold
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = "📸 Сфотографировать результат",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onHome,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.accentGold,
                    contentColor = MaterialTheme.colorScheme.background
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
        }
    }
}
