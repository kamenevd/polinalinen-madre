package com.polinalinen.madre.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File

/**
 * «Старое фото» (DESIGN-V4.md Cycle 6, AgedPhoto): PhotoPicker и камера выдают
 * временный content-URI, доступ к которому не переживает процесс — поэтому в
 * БД (BakeRecord.photoPath, FeedingEntity.photoPath) никогда не попадает URI,
 * только путь к файлу внутри приложения — с Cycle 15 относительно filesDir,
 * см. [commitBitmap] и [resolve].
 *
 * Cycle 11 разделил дорогу снимка на два шага, потому что между выбором кадра
 * и вклейкой встал редактор оформления (ui/photo/PhotoDesigner):
 *
 *  1. [stage] / [stagingFile] — кадр ложится во ВРЕМЕННЫЙ файл в cacheDir.
 *     Пока человек не нажал «готово», в книге его нет, а [discard] его убирает.
 *  2. [commit] / [commitBitmap] — окончательный файл в filesDir; только его
 *     путь уходит в Room.
 *
 * Обе точки добавления фото (кормление и готовая выпечка) ходят сюда через
 * общий ui/photo/PhotoAttachment, поэтому логика копирования, поворота и
 * сжатия существует ровно в одном экземпляре.
 */
object PhotoStore {

    /** Куда и с каким префиксом кладём — единая точка истины по именам файлов. */
    enum class PhotoKind(val dirName: String, val prefix: String) {
        BAKE("bake_photos", "bake"),
        FEEDING("feeding_photos", "feed"),
    }

    /** Совпадает с `cache-path` в res/xml/file_paths.xml — камера пишет туда же. */
    private const val STAGING_DIR = "camera"

    /** Длинная сторона итогового кадра. Больше книге не нужно, а память бережёт. */
    const val MAX_EDGE_PX = 1600

    private const val JPEG_QUALITY = 92

    /** Пустой временный файл в кэше — камера получит на него FileProvider-URI. */
    fun stagingFile(context: Context, kind: PhotoKind): File {
        val dir = File(context.cacheDir, STAGING_DIR).apply { mkdirs() }
        return File(dir, "${kind.prefix}_${System.currentTimeMillis()}.jpg")
    }

    /** Копирует выбранный в галерее кадр во временный файл; null — если не вышло. */
    fun stage(context: Context, source: Uri, kind: PhotoKind): File? = runCatching {
        val file = stagingFile(context, kind)
        context.contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        file
    }.getOrNull()

    /**
     * Переносит временный файл в filesDir как есть — это «без оформления».
     * Кадр всё равно проходит через декодирование, чтобы снять EXIF-поворот:
     * иначе снятое камерой боком фото ляжет в книгу лёжа.
     */
    fun commit(context: Context, staged: File, kind: PhotoKind, key: Long): String? {
        val bitmap = decodeUpright(staged) ?: return null
        val path = commitBitmap(context, bitmap, kind, key)
        bitmap.recycle()
        return path
    }

    /**
     * Сохраняет готовый кадр редактора; возвращает ОТНОСИТЕЛЬНЫЙ путь
     * («bake_photos/bake_12_1690000000000.jpg») или null.
     *
     * Cycle 15: раньше в Room уезжал absolutePath, а он у приложения не вечен —
     * filesDir переезжает между /data/data/… и /data/user/N/… (второй профиль,
     * рабочий профиль, перенос данных). Абсолютный путь после такого переезда
     * указывает в никуда, и книга теряет фотокарточки целыми страницами.
     * Поэтому в БД ложится путь ОТНОСИТЕЛЬНО filesDir, а собирает его обратно
     * [resolve] — уже по сегодняшнему filesDir.
     */
    fun commitBitmap(context: Context, bitmap: Bitmap, kind: PhotoKind, key: Long): String? = runCatching {
        val relative = relativePath(kind, key)
        val file = File(context.filesDir, relative).apply { parentFile?.mkdirs() }
        file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        stripGpsExif(file)
        relative
    }.getOrNull()

    /** Имя новой фотокарточки относительно filesDir — единая точка истины. */
    fun relativePath(kind: PhotoKind, key: Long): String =
        "${kind.dirName}/${kind.prefix}_${key}_${System.currentTimeMillis()}.jpg"

    /**
     * Путь из БД → файл на диске. Старые записи (до Cycle 15 и те, что не
     * поймала MIGRATION_6_7) хранят абсолютный путь — их отдаём как есть, иначе
     * получилось бы filesDir + «/data/user/0/…». Всё остальное — относительно
     * filesDir.
     */
    fun resolve(context: Context, relativeOrAbsolutePath: String): File =
        if (relativeOrAbsolutePath.startsWith("/")) {
            File(relativeOrAbsolutePath) // legacy absolute path
        } else {
            File(context.filesDir, relativeOrAbsolutePath)
        }

    /**
     * Фотокарточку правда можно открыть. Исключения гасим: снимок мог лежать на
     * вынутой карте памяти, и разворот Полки не должен падать из-за одной
     * пропавшей фотографии.
     */
    fun isReadable(context: Context, path: String): Boolean =
        runCatching { resolve(context, path).let { it.isFile && it.canRead() } }.getOrDefault(false)

    /**
     * Вычищает координаты съёмки из EXIF готового файла.
     *
     * Сегодня это страховка, а не заплата на утечку: [commitBitmap] пересобирает
     * кадр через Bitmap.compress, а тот пишет JPEG вообще без EXIF — GPS туда
     * не доезжает уже сейчас. Но фотокарточка уходит в общий доступ (гостевая
     * страница, экспорт книги), и цена ошибки — домашний адрес семьи. Поэтому
     * чистка стоит на самом выходе: если путь сохранения когда-нибудь станет
     * побайтовым копированием оригинала, координаты всё равно не пройдут.
     *
     * [commit] отдельно вызывать не нужно — он сохраняет ровно через [commitBitmap].
     */
    fun stripGpsExif(file: File): Boolean = runCatching {
        val exif = ExifInterface(file.path)
        GPS_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
        exif.saveAttributes()
        true
    }.getOrDefault(false)

    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
    )

    /** Убирает временный файл. Вызывается и на «отмена», и после удачной вклейки. */
    fun discard(staged: File?) {
        runCatching { staged?.delete() }
    }

    /**
     * Декодирует файл, ужимая до [maxEdge] по длинной стороне и разворачивая
     * по EXIF. Отдельная функция, потому что нужна дважды: для превью в
     * редакторе (меньше) и для итогового кадра ([MAX_EDGE_PX]).
     */
    fun decodeUpright(file: File, maxEdge: Int = MAX_EDGE_PX): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        val rotation = exifRotation(file)
        if (rotation == 0f) return decoded

        val matrix = Matrix().apply { postRotate(rotation) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        rotated
    }.getOrNull()

    /** Степень двойки, при которой длинная сторона впервые влезает в [maxEdge]. */
    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        if (maxEdge <= 0) return 1
        var sample = 1
        while (maxOf(width, height) / sample > maxEdge) sample *= 2
        return sample
    }

    private fun exifRotation(file: File): Float = runCatching {
        val orientation = ExifInterface(file.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
}
