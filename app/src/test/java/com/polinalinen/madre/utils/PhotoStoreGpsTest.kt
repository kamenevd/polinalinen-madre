package com.polinalinen.madre.utils

import android.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Cycle 15: фотокарточка из книги уходит наружу — на гостевую страницу и в
 * экспорт, — а снятый телефоном кадр несёт в EXIF координаты кухни. Отдать
 * вместе с хлебом домашний адрес семьи нельзя, поэтому [PhotoStore.stripGpsExif]
 * стоит на выходе сохранения.
 *
 * Кадр здесь — настоящий JPEG из test/resources, а не мок: подменённый
 * ExifInterface проверял бы сам себя, а вопрос ровно в том, доживают ли
 * GPS-теги до файла на диске.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoStoreGpsTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Все теги, которые обязаны исчезнуть, вместе с правдоподобными значениями. */
    private val gpsTags = mapOf(
        ExifInterface.TAG_GPS_LATITUDE to "55/1,45/1,12/1",
        ExifInterface.TAG_GPS_LATITUDE_REF to "N",
        ExifInterface.TAG_GPS_LONGITUDE to "37/1,37/1,2/1",
        ExifInterface.TAG_GPS_LONGITUDE_REF to "E",
        ExifInterface.TAG_GPS_ALTITUDE to "156/1",
        ExifInterface.TAG_GPS_ALTITUDE_REF to "0",
        ExifInterface.TAG_GPS_TIMESTAMP to "10:32:15",
        ExifInterface.TAG_GPS_DATESTAMP to "2026:08:05",
    )

    private fun plainJpeg(name: String): File {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/photo/plain_16x16.jpg")) {
            "не нашёлся тестовый JPEG src/test/resources/photo/plain_16x16.jpg"
        }.use { it.readBytes() }
        return temp.newFile(name).apply { writeBytes(bytes) }
    }

    private fun jpegWithGps(name: String = "shot.jpg"): File {
        val file = plainJpeg(name)
        val exif = ExifInterface(file.path)
        gpsTags.forEach { (tag, value) -> exif.setAttribute(tag, value) }
        exif.saveAttributes()
        return file
    }

    /** Хвост файла — это сжатые пиксели: чистка EXIF не имеет права их трогать. */
    private fun scanTail(file: File): List<Byte> = file.readBytes().takeLast(128).toList()

    @Test
    fun `the fixture really carries coordinates before stripping`() {
        val exif = ExifInterface(jpegWithGps().path)
        gpsTags.forEach { (tag, value) ->
            assertThat(exif.getAttribute(tag)).isEqualTo(value)
        }
    }

    @Test
    fun `stripping removes every gps tag`() {
        val file = jpegWithGps()

        assertThat(PhotoStore.stripGpsExif(file)).isTrue()

        val exif = ExifInterface(file.path)
        gpsTags.keys.forEach { tag ->
            assertThat(exif.getAttribute(tag)).isNull()
        }
    }

    @Test
    fun `stripping keeps the picture itself intact`() {
        val original = plainJpeg("original.jpg")
        val file = jpegWithGps("stripped.jpg")

        PhotoStore.stripGpsExif(file)

        val bytes = file.readBytes()
        assertThat(bytes.size).isAtLeast(2)
        // SOI/EOI на месте — файл всё ещё JPEG, а не обрезанный огрызок.
        assertThat(bytes[0]).isEqualTo(0xFF.toByte())
        assertThat(bytes[1]).isEqualTo(0xD8.toByte())
        assertThat(bytes[bytes.size - 2]).isEqualTo(0xFF.toByte())
        assertThat(bytes[bytes.size - 1]).isEqualTo(0xD9.toByte())
        assertThat(scanTail(file)).isEqualTo(scanTail(original))
    }

    @Test
    fun `stripping a file without exif is a no-op, not a crash`() {
        val file = plainJpeg("plain.jpg")
        val tailBefore = scanTail(file)

        PhotoStore.stripGpsExif(file)

        val exif = ExifInterface(file.path)
        gpsTags.keys.forEach { tag ->
            assertThat(exif.getAttribute(tag)).isNull()
        }
        assertThat(scanTail(file)).isEqualTo(tailBefore)
    }

    @Test
    fun `a missing file is reported, not thrown`() {
        assertThat(PhotoStore.stripGpsExif(File(temp.root, "nope.jpg"))).isFalse()
    }
}
