package com.polinalinen.madre.utils

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * «Старое фото» (DESIGN-V4.md Cycle 6, AgedPhoto): PhotoPicker выдаёт временный
 * content-URI, доступ к которому не переживает процесс — копируем файл в
 * internal storage и дальше живём только с абсолютным путём (BakeRecord.photoPath).
 */
object PhotoStore {

    private const val DIR = "bake_photos"

    /** Копирует выбранный снимок внутрь приложения; null — если копия не удалась. */
    fun saveBakePhoto(context: Context, source: Uri, sessionKey: Long): String? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val file = File(dir, "bake_${sessionKey}_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        file.absolutePath
    }.getOrNull()
}
