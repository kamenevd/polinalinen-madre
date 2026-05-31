package com.polinalinen.madre.sourdough

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.ui.theme.AppColors
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourdoughScreen(
    viewModel: SourdoughViewModel = viewModel(),
    onBack: () -> Unit = {},
    onOpenGallery: (photos: List<String>, startIndex: Int) -> Unit = { _, _ -> }
) {
    val state by viewModel.uiState.collectAsState()
    val isEditing by viewModel.isEditingConfig.collectAsState()
    val context = LocalContext.current

    // Camera state
    var pendingCameraFeedingId by remember { mutableStateOf<Int?>(null) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && pendingCameraFeedingId != null) {
            cameraImageUri?.path?.let { path ->
                viewModel.attachPhoto(pendingCameraFeedingId!!, path)
            }
        }
        pendingCameraFeedingId = null
        cameraImageUri = null
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingCameraFeedingId != null) {
            launchCamera(context, pendingCameraFeedingId!!) { uri ->
                cameraImageUri = uri
                cameraLauncher.launch(uri)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Назад",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "🍶 ${state.config.name}",
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.accentGold
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // Status card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (state.isOverdue)
                                MaterialTheme.colorScheme.errorContainer
                            else
                                MaterialTheme.colorScheme.surface
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Последнее кормление:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.lastFeedingText,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (state.lastFeedingAgoText.isNotEmpty()) {
                                Text(
                                    text = state.lastFeedingAgoText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.accentGold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Интервал: ${formatInterval(state.config.intervalHours)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (state.nextFeedingText.isNotEmpty()) {
                                Text(
                                    text = state.nextFeedingText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (state.isOverdue) MaterialTheme.colorScheme.error
                                    else AppColors.accentGold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Buttons row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.recordFeeding() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Покормил ✅")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.toggleEditConfig() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Настройки ⚙️")
                                }
                            }
                        }
                    }
                }

                // Config editing
                if (isEditing) {
                    item {
                        ConfigEditor(
                            currentConfig = state.config,
                            onSave = { name, interval -> viewModel.updateConfig(name, interval) },
                            onCancel = { viewModel.toggleEditConfig() }
                        )
                    }
                }

                // History header
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "── История кормлений ──",
                        style = MaterialTheme.typography.labelLarge,
                        color = AppColors.accentGold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Feeding history
                if (state.feedings.isEmpty()) {
                    item {
                        Text(
                            text = "Пока нет записей.\nНажмите «Покормил ✅» чтобы начать.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(state.feedings) { feeding ->
                        val isFirst = feeding == state.feedings.first()

                        FeedingCard(
                            feeding = feeding,
                            isFirst = isFirst,
                            onAddPhoto = {
                                pendingCameraFeedingId = feeding.id
                                // Check camera permission
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    permissionLauncher.launch(android.Manifest.permission.CAMERA)
                                } else {
                                    launchCamera(context, feeding.id) { uri ->
                                        cameraImageUri = uri
                                        cameraLauncher.launch(uri)
                                    }
                                }
                            },
                            onPhotoClick = {
                                val photoIndex = state.photos.indexOf(feeding.photoPath)
                                if (photoIndex >= 0) {
                                    onOpenGallery(state.photos, photoIndex)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedingCard(
    feeding: Feeding,
    isFirst: Boolean,
    onAddPhoto: () -> Unit,
    onPhotoClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = SourdoughViewModel.formatDateShort(feeding.timestamp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Photo thumbnail or add button
            if (feeding.photoPath != null) {
                val photoFile = File(feeding.photoPath)
                if (photoFile.exists()) {
                    androidx.compose.foundation.Image(
                        bitmap = android.graphics.BitmapFactory.decodeFile(feeding.photoPath)
                            .asImageBitmap(),
                        contentDescription = "Фото",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)
                            )
                            .clickable(onClick = onPhotoClick),
                        contentScale = ContentScale.Crop
                    )
                }
            } else if (isFirst) {
                // Only show camera button on the latest entry without photo
                OutlinedButton(
                    onClick = onAddPhoto,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("📸")
                }
            }
        }
    }
}

@Composable
private fun ConfigEditor(
    currentConfig: SourdoughConfig,
    onSave: (String, Int) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(currentConfig.name) }
    var selectedInterval by remember { mutableStateOf(currentConfig.intervalHours) }

    val intervals = listOf(12, 24, 48, 72, 168)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Настройки",
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.accentGold
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Интервал кормления:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            intervals.forEach { hours ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedInterval = hours }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedInterval == hours,
                        onClick = { selectedInterval = hours }
                    )
                    Text(
                        text = formatInterval(hours),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) {
                    Text("Отмена")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onSave(name, selectedInterval) }) {
                    Text("Сохранить")
                }
            }
        }
    }
}

private fun formatInterval(hours: Int): String = when (hours) {
    12 -> "Каждые 12 часов"
    24 -> "Каждый день"
    48 -> "Каждые 2 дня"
    72 -> "Каждые 3 дня"
    168 -> "Каждую неделю"
    else -> "Каждые $hours ч"
}

private fun launchCamera(
    context: android.content.Context,
    feedingId: Int,
    onUriReady: (Uri) -> Unit
) {
    val photoDir = File(context.filesDir, "sourdough_gallery")
    if (!photoDir.exists()) photoDir.mkdirs()
    val photoFile = File(photoDir, "sourdough_${feedingId}_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        photoFile
    )
    onUriReady(uri)
}
