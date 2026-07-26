package com.polinalinen.madre.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Cycle 11: прямой доступ к камере (ACTION_IMAGE_CAPTURE, не только PhotoPicker).
 *
 * До этого цикла вклеить фотокарточку можно было ТОЛЬКО из галереи — камеры не
 * было, и снимок приходилось делать «где-то ещё», а потом возвращаться в книгу.
 * Здесь остаётся ровно одна обязанность: выдать камере content-URI на пустой
 * файл в кэше. Всё, что дальше — оформление и вклейка — общая механика
 * [PhotoStore] и ui/photo/PhotoAttachment.
 */
object CameraCapture {

    /**
     * Создаёт пустой файл в кэше приложения и возвращает публичный URI, в
     * который OS запишет JPEG. Файл ВСЕГДА временный: пока человек не нажал
     * «готово» в редакторе, в книге снимка нет.
     *
     * FileProvider authority — `${packageName}.fileprovider` (см. манифест и
     * res/xml/file_paths.xml, где `camera/` объявлен как cache-path).
     */
    fun outputUriFor(context: Context, kind: PhotoStore.PhotoKind): CaptureTarget {
        val tmp = PhotoStore.stagingFile(context, kind)
        // Гарантируем пустой файл — некоторые камеры-приложения отказываются
        // писать, если файл уже существует с ненулевым размером.
        tmp.outputStream().use { /* truncate */ }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmp)
        return CaptureTarget(tmp = tmp, uri = uri)
    }

    /** Прозрачная пара — файл и его FileProvider-URI. */
    data class CaptureTarget(val tmp: File, val uri: Uri)
}
