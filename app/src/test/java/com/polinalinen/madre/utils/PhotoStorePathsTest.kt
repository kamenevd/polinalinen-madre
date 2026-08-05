package com.polinalinen.madre.utils

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Cycle 15: в Room уезжал File.absolutePath, а filesDir у приложения не вечен —
 * он переезжает между /data/data/… и /data/user/N/… (второй профиль, рабочий
 * профиль, перенос данных). После переезда абсолютный путь указывает в никуда,
 * и книга теряет фотокарточки целыми страницами.
 *
 * Поэтому в БД ложится путь ОТНОСИТЕЛЬНО filesDir, а собирает файл обратно
 * [PhotoStore.resolve] — по сегодняшнему filesDir. Старые абсолютные записи он
 * обязан понимать по-прежнему: MIGRATION_6_7 переписала не все.
 */
@RunWith(RobolectricTestRunner::class)
class PhotoStorePathsTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a new photo path is relative and sits in its own folder`() {
        PhotoStore.PhotoKind.entries.forEach { kind ->
            val path = PhotoStore.relativePath(kind, key = 12)

            // Главное свойство: путь НЕ абсолютный, иначе всё лечение напрасно.
            assertThat(path).doesNotContain("/data/")
            assertThat(path.startsWith("/")).isFalse()
            assertThat(path).startsWith("${kind.dirName}/")
            assertThat(path).endsWith(".jpg")
            assertThat(File(path).name).startsWith("${kind.prefix}_12_")
        }
    }

    @Test
    fun `bake and feeding photos never collide`() {
        val bake = PhotoStore.relativePath(PhotoStore.PhotoKind.BAKE, key = 1)
        val feeding = PhotoStore.relativePath(PhotoStore.PhotoKind.FEEDING, key = 1)
        assertThat(bake.substringBefore('/')).isNotEqualTo(feeding.substringBefore('/'))
    }

    @Test
    fun `a relative path is resolved against the current filesDir`() {
        val resolved = PhotoStore.resolve(context, "bake_photos/bake_1_2.jpg")

        assertThat(resolved).isEqualTo(File(context.filesDir, "bake_photos/bake_1_2.jpg"))
        assertThat(resolved.isAbsolute).isTrue()
        assertThat(resolved.path).startsWith(context.filesDir.path)
    }

    @Test
    fun `a legacy absolute path is left alone`() {
        val legacy = "/data/user/0/com.polinalinen.madre/files/bake_photos/bake_1_2.jpg"

        // Иначе получился бы filesDir + «/data/user/0/…», и старые снимки,
        // которые миграция не поймала, перестали бы открываться совсем.
        assertThat(PhotoStore.resolve(context, legacy)).isEqualTo(File(legacy))
        assertThat(PhotoStore.resolve(context, legacy).path).isEqualTo(legacy)
    }

    @Test
    fun `a photo saved today is found by the path that goes into the database`() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

        val stored = PhotoStore.commitBitmap(context, bitmap, PhotoStore.PhotoKind.BAKE, key = 42)

        assertThat(stored).isNotNull()
        assertThat(stored!!.startsWith("/")).isFalse()
        assertThat(stored).startsWith("bake_photos/")
        // Круг замкнулся: что записали в БД — то и открывается с диска.
        assertThat(PhotoStore.resolve(context, stored).isFile).isTrue()
        assertThat(PhotoStore.isReadable(context, stored)).isTrue()
    }

    @Test
    fun `a photo that is gone is reported as unreadable, not as a crash`() {
        assertThat(PhotoStore.isReadable(context, "bake_photos/never_existed.jpg")).isFalse()
        assertThat(PhotoStore.isReadable(context, "/data/user/0/nope/never_existed.jpg")).isFalse()
    }
}
