package com.polinalinen.madre.ui.components

import androidx.compose.ui.graphics.ColorMatrix
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DESIGN-V4.md Cycle 6, фича «Старое фото» (AgedPhoto): фотокарточка стареет
 * вместе с записью — до недели цветная, до месяца сепия, дальше выцветшая.
 * Проверяем пороги стадий и характер матриц: свежее фото не трогается вовсе,
 * сепия — тёплая (красный канал сильнее синего), выцветшее — засвеченное.
 */
class PhotoAgingTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `fresh photo stays fresh until a full week passes`() {
        assertThat(PhotoAging.stageFor(0)).isEqualTo(PhotoAging.Stage.FRESH)
        assertThat(PhotoAging.stageFor(6 * day)).isEqualTo(PhotoAging.Stage.FRESH)
        assertThat(PhotoAging.stageFor(PhotoAging.WEEK_MILLIS - 1)).isEqualTo(PhotoAging.Stage.FRESH)
    }

    @Test
    fun `sepia begins exactly at one week and lasts until a month`() {
        assertThat(PhotoAging.stageFor(PhotoAging.WEEK_MILLIS)).isEqualTo(PhotoAging.Stage.SEPIA)
        assertThat(PhotoAging.stageFor(29 * day)).isEqualTo(PhotoAging.Stage.SEPIA)
        assertThat(PhotoAging.stageFor(PhotoAging.MONTH_MILLIS - 1)).isEqualTo(PhotoAging.Stage.SEPIA)
    }

    @Test
    fun `faded begins exactly at one month`() {
        assertThat(PhotoAging.stageFor(PhotoAging.MONTH_MILLIS)).isEqualTo(PhotoAging.Stage.FADED)
        assertThat(PhotoAging.stageFor(365 * day)).isEqualTo(PhotoAging.Stage.FADED)
    }

    @Test
    fun `fresh matrix is identity — the photo is untouched`() {
        assertThat(PhotoAging.colorMatrix(0).values).isEqualTo(ColorMatrix().values)
    }

    @Test
    fun `sepia matrix is warm — red row outweighs blue row`() {
        val m = PhotoAging.colorMatrix(PhotoAging.WEEK_MILLIS).values
        val redRow = m[0] + m[1] + m[2]
        val blueRow = m[10] + m[11] + m[12]
        assertThat(redRow).isGreaterThan(blueRow)
    }

    @Test
    fun `sepia matrix flattens color into a single tone`() {
        // После setToSaturation(0) чистые R/G/B дают один и тот же тон:
        // внутри строки коэффициенты пропорциональны luminance-весам,
        // поэтому зелёный вес строго больше красного и синего.
        val m = PhotoAging.colorMatrix(PhotoAging.WEEK_MILLIS).values
        assertThat(m[1]).isGreaterThan(m[0])
        assertThat(m[1]).isGreaterThan(m[2])
    }

    @Test
    fun `faded matrix lightens the photo — positive offsets and softer contrast`() {
        val faded = PhotoAging.colorMatrix(PhotoAging.MONTH_MILLIS).values
        val sepia = PhotoAging.colorMatrix(PhotoAging.WEEK_MILLIS).values
        // Смещения каналов (столбец 5) заметно положительные — общая засветка.
        assertThat(faded[4]).isGreaterThan(sepia[4])
        assertThat(faded[9]).isGreaterThan(sepia[9])
        assertThat(faded[14]).isGreaterThan(sepia[14])
        // Контраст упал: масштаб красной строки меньше, чем у сепии.
        assertThat(faded[0] + faded[1] + faded[2]).isLessThan(sepia[0] + sepia[1] + sepia[2])
    }

    @Test
    fun `alpha channel is never touched by aging`() {
        listOf(0L, PhotoAging.WEEK_MILLIS, PhotoAging.MONTH_MILLIS).forEach { age ->
            val m = PhotoAging.colorMatrix(age).values
            assertThat(m[15]).isEqualTo(0f)
            assertThat(m[16]).isEqualTo(0f)
            assertThat(m[17]).isEqualTo(0f)
            assertThat(m[18]).isEqualTo(1f)
            assertThat(m[19]).isEqualTo(0f)
        }
    }
}
