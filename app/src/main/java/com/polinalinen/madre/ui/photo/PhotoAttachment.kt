package com.polinalinen.madre.ui.photo

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.polinalinen.madre.ui.components.ConfirmDialog
import com.polinalinen.madre.ui.components.PhotoSourceChooser
import com.polinalinen.madre.utils.CameraCapture
import com.polinalinen.madre.utils.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
    onCancelled: () -> Unit = {},
    onAttached: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attached by rememberUpdatedState(onAttached)
    val cancelled by rememberUpdatedState(onCancelled)

    var chooserVisible by remember { mutableStateOf(false) }
    // Cycle 17: пути, а не File, и rememberSaveable — камера часто убивает
    // процесс, и remember терял кадр молча (success=true, file=null).
    var stagedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var road by rememberSaveable { mutableStateOf(PhotoRoad()) }
    val staged = stagedPath?.let { File(it) }

    fun reportCancelOnce() {
        val result = road.cancel()
        road = result.next
        if (result.shouldNotify) cancelled()
    }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            reportCancelOnce()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            stagedPath = withContext(Dispatchers.IO) { PhotoStore.stage(context, uri, kind)?.absolutePath }
        }
    }

    // Cycle 12: отказ в камере перестал быть тишиной. Раньше человек нажимал
    // «Камера», отказывал в разрешении — и книга просто ничего не делала, как
    // будто кнопка сломана. Теперь она честно говорит, что произошло, и
    // предлагает галерею: снимок можно вклеить и без камеры.
    var cameraDenied by remember { mutableStateOf(false) }

    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraPath?.let { File(it) }
        pendingCameraPath = null
        // Пустой файл — это отменённая съёмка на части прошивок: success там
        // приходит true, а JPEG так и не записан.
        if (success && file != null && file.length() > 0L) {
            stagedPath = file.absolutePath
        } else {
            PhotoStore.discard(file)
            reportCancelOnce()
        }
    }

    fun launchCamera() {
        val target = CameraCapture.outputUriFor(context, kind)
        pendingCameraPath = target.tmp.absolutePath
        try {
            cameraCapture.launch(target.uri)
        } catch (_: ActivityNotFoundException) {
            pendingCameraPath = null
            PhotoStore.discard(target.tmp)
            cameraDenied = true
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else cameraDenied = true
    }

    if (cameraDenied) {
        // Обновление APK с тем же signing key runtime-разрешения НЕ сбрасывает.
        // Если кажется, что «каждый раз слетает» — чаще это переустановка
        // (другая подпись debug/release) или «запретить навсегда» в системе.
        val openSettings: () -> Unit = {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
            runCatching { context.startActivity(intent) }
        }
        ConfirmDialog(
            title = "Камера закрыта",
            message = "Книге не разрешили снимать. Можно вклеить снимок из галереи " +
                "(для неё отдельное разрешение не нужно) или открыть настройки приложения " +
                "и вернуть доступ к камере. Обновление с тем же ключом подписи разрешения не сбрасывает.",
            confirmLabel = "Из галереи",
            dismissLabel = "Настройки",
            onConfirm = {
                cameraDenied = false
                galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onDismiss = {
                cameraDenied = false
                reportCancelOnce()
                openSettings()
            },
        )
    }

    PhotoSourceChooser(
        visible = chooserVisible,
        onDismiss = {
            chooserVisible = false
            reportCancelOnce()
        },
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
                stagedPath = null
                PhotoStore.discard(file)
                reportCancelOnce()
            },
            onSaved = { path ->
                stagedPath = null
                PhotoStore.discard(file)
                road = road.attached()
                attached(path)
            },
        )
    }

    return {
        road = road.begin()
        chooserVisible = true
    }
}
