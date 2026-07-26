package com.polinalinen.madre.ui.photo

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.polinalinen.madre.ui.components.PhotoSourceChooser
import com.polinalinen.madre.utils.CameraCapture
import com.polinalinen.madre.utils.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Общая механика «добавить фотокарточку» для всех точек книги (Cycle 11).
 *
 * Раньше экран кормления и экран готовой выпечки держали по своей копии этой
 * цепочки — два набора launcher'ов, два места, где можно забыть про разрешение
 * камеры или про временный файл. Теперь дорога одна и живёт здесь:
 *
 *   тап → модалка «Камера / Галерея» → кадр во временный файл в кэше →
 *   «Стол оформления» ([PhotoDesignerDialog]) → абсолютный путь в filesDir
 *
 * Камера и галерея равноправны: обе приводят ровно в один и тот же редактор,
 * и обе отдают наружу только абсолютный путь — content-URI до Room не доходит.
 *
 * @param kind в какую папку filesDir ляжет итог (выпечка или кормление).
 * @param key ключ записи для имени файла; 0 — если записи ещё нет (форма кормления).
 * @param onAttached вызывается ровно один раз на удачную вклейку.
 * @return открыть выбор источника — то, что вешается на слот фотокарточки.
 */
@Composable
fun rememberPhotoAttachment(
    kind: PhotoStore.PhotoKind,
    key: Long,
    onAttached: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attached by rememberUpdatedState(onAttached)

    var chooserVisible by remember { mutableStateOf(false) }
    // Кадр, который сейчас лежит на столе оформления. Пока он не null —
    // редактор открыт, а файл существует только в кэше.
    var staged by remember { mutableStateOf<File?>(null) }
    // Файл, в который пишет камера. Держим отдельно: TakePicture отдаёт лишь
    // boolean, и без этой ссылки прочитать снимок будет неоткуда.
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            staged = withContext(Dispatchers.IO) { PhotoStore.stage(context, uri, kind) }
        }
    }

    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        // Пустой файл — это отменённая съёмка на части прошивок: success там
        // приходит true, а JPEG так и не записан.
        if (success && file != null && file.length() > 0L) staged = file else PhotoStore.discard(file)
    }

    fun launchCamera() {
        val target = CameraCapture.outputUriFor(context, kind)
        pendingCameraFile = target.tmp
        cameraCapture.launch(target.uri)
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    PhotoSourceChooser(
        visible = chooserVisible,
        onDismiss = { chooserVisible = false },
        onPickGallery = {
            galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onPickCamera = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) launchCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
        },
    )

    staged?.let { file ->
        PhotoDesignerDialog(
            staged = file,
            kind = kind,
            key = key,
            onCancel = {
                staged = null
                PhotoStore.discard(file)
            },
            onSaved = { path ->
                staged = null
                PhotoStore.discard(file)
                attached(path)
            },
        )
    }

    return { chooserVisible = true }
}
