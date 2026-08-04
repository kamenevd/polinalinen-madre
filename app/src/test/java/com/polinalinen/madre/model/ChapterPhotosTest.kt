package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import org.junit.Test

/**
 * Cycle 14: у главы с выпечкой на полке появляется лицо — последнее фото.
 *
 * Отбор снимков считается здесь, а не в композиции экрана: плитка обязана взять
 * ИМЕННО последнее доступное фото своей главы, а viewer — листать ровно её
 * снимки и ничьи больше. Оба правила легко нарушить незаметно, если решать это
 * по ходу отрисовки.
 */
class ChapterPhotosTest {

    private fun record(
        id: Long,
        recipeId: String = "bread",
        photoPath: String? = "/photos/$id.jpg",
        completedAtMillis: Long = id * 1000L,
    ) = BakeRecordEntity(
        id = id,
        recipeId = recipeId,
        recipeName = "Бородинский",
        portions = 1,
        completedAtMillis = completedAtMillis,
        photoPath = photoPath,
    )

    @Test
    fun `a chapter shows its own photos and nobody else's`() {
        val records = listOf(
            record(1, recipeId = "bread"),
            record(2, recipeId = "focaccia"),
            record(3, recipeId = "bread"),
        )
        val photos = ChapterPhotos.of(records, "bread")
        assertThat(photos.map { it.recordId }).containsExactly(3L, 1L).inOrder()
    }

    @Test
    fun `the newest photo comes first, so the viewer opens on it`() {
        val records = listOf(
            record(1, completedAtMillis = 100),
            record(2, completedAtMillis = 900),
            record(3, completedAtMillis = 500),
        )
        assertThat(ChapterPhotos.of(records, "bread").map { it.recordId })
            .containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test
    fun `the cover is the newest photo of the chapter`() {
        val records = listOf(record(1, completedAtMillis = 100), record(2, completedAtMillis = 900))
        assertThat(ChapterPhotos.cover(records, "bread")?.recordId).isEqualTo(2L)
    }

    /** Честный fallback: главу пекли, но не фотографировали — снимка нет. */
    @Test
    fun `a chapter baked without a camera has no cover at all`() {
        val records = listOf(record(1, photoPath = null), record(2, photoPath = null))
        assertThat(ChapterPhotos.cover(records, "bread")).isNull()
        assertThat(ChapterPhotos.of(records, "bread")).isEmpty()
    }

    @Test
    fun `a chapter never baked has nothing to show either`() {
        assertThat(ChapterPhotos.cover(emptyList(), "bread")).isNull()
        assertThat(ChapterPhotos.of(emptyList(), "bread")).isEmpty()
    }

    /**
     * Пустая строка в photoPath — не путь, а мусор из старой записи. Плитка,
     * доверившаяся ей, показала бы пустую рамку вместо честного fallback'а.
     */
    @Test
    fun `a blank path is not a photo`() {
        val records = listOf(record(1, photoPath = "   "), record(2, photoPath = ""), record(3))
        assertThat(ChapterPhotos.of(records, "bread").map { it.recordId }).containsExactly(3L)
    }

    @Test
    fun `photos without a picture do not hide the ones that have it`() {
        val records = listOf(
            record(1, photoPath = null, completedAtMillis = 900),
            record(2, completedAtMillis = 100),
        )
        // Самая свежая выпечка без фото не делает главу безликой.
        assertThat(ChapterPhotos.cover(records, "bread")?.recordId).isEqualTo(2L)
    }

    @Test
    fun `a photo carries what its caption needs`() {
        val photo = ChapterPhotos.of(listOf(record(7, completedAtMillis = 1_700_000_000_000L)), "bread").single()
        assertThat(photo.path).isEqualTo("/photos/7.jpg")
        assertThat(photo.recipeName).isEqualTo("Бородинский")
        assertThat(photo.takenAtMillis).isEqualTo(1_700_000_000_000L)
    }

    // --- границы листания ---

    @Test
    fun `the viewer opens on the first photo of the list, which is the newest`() {
        assertThat(ChapterPhotos.startIndex(size = 5)).isEqualTo(0)
        assertThat(ChapterPhotos.startIndex(size = 0)).isEqualTo(0)
    }

    @Test
    fun `paging never runs off either end of the chapter`() {
        assertThat(ChapterPhotos.clampIndex(index = -1, size = 3)).isEqualTo(0)
        assertThat(ChapterPhotos.clampIndex(index = 0, size = 3)).isEqualTo(0)
        assertThat(ChapterPhotos.clampIndex(index = 2, size = 3)).isEqualTo(2)
        assertThat(ChapterPhotos.clampIndex(index = 9, size = 3)).isEqualTo(2)
    }

    @Test
    fun `an empty chapter clamps to zero instead of to minus one`() {
        assertThat(ChapterPhotos.clampIndex(index = 4, size = 0)).isEqualTo(0)
    }

    @Test
    fun `the counter counts from one, the way people do`() {
        assertThat(ChapterPhotos.counterText(index = 0, size = 7)).isEqualTo("1 из 7")
        assertThat(ChapterPhotos.counterText(index = 6, size = 7)).isEqualTo("7 из 7")
        // Единственный снимок считать незачем.
        assertThat(ChapterPhotos.counterText(index = 0, size = 1)).isEmpty()
        assertThat(ChapterPhotos.counterText(index = 0, size = 0)).isEmpty()
    }
}
