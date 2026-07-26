package com.polinalinen.madre.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 11: у снимков из камеры разрешение легко уходит за 4000 px, а книге
 * хватает 1600 — иначе редактор оформления держит в памяти кадр, который
 * никуда не помещается. Подбор inSampleSize — чистая арифметика, её и проверяем.
 */
class PhotoStoreTest {

    @Test
    fun `small photos are decoded as they are`() {
        assertThat(PhotoStore.sampleSizeFor(800, 600, PhotoStore.MAX_EDGE_PX)).isEqualTo(1)
        assertThat(PhotoStore.sampleSizeFor(1600, 1200, PhotoStore.MAX_EDGE_PX)).isEqualTo(1)
    }

    @Test
    fun `sample size is a power of two and brings the long edge under the limit`() {
        listOf(2000 to 1500, 4032 to 3024, 8000 to 6000).forEach { (w, h) ->
            val sample = PhotoStore.sampleSizeFor(w, h, PhotoStore.MAX_EDGE_PX)
            assertThat(Integer.bitCount(sample)).isEqualTo(1)
            assertThat(maxOf(w, h) / sample).isAtMost(PhotoStore.MAX_EDGE_PX)
        }
    }

    @Test
    fun `portrait and landscape shots of the same frame scale identically`() {
        assertThat(PhotoStore.sampleSizeFor(4032, 3024, 1600))
            .isEqualTo(PhotoStore.sampleSizeFor(3024, 4032, 1600))
    }

    @Test
    fun `preview limit shrinks harder than the saved frame`() {
        assertThat(PhotoStore.sampleSizeFor(4032, 3024, 900))
            .isAtLeast(PhotoStore.sampleSizeFor(4032, 3024, PhotoStore.MAX_EDGE_PX))
    }

    @Test
    fun `a nonsense limit never returns a sample size that divides by zero`() {
        assertThat(PhotoStore.sampleSizeFor(4032, 3024, 0)).isEqualTo(1)
        assertThat(PhotoStore.sampleSizeFor(4032, 3024, -10)).isEqualTo(1)
    }

    @Test
    fun `bake and feeding photos never share a folder or a name prefix`() {
        val bake = PhotoStore.PhotoKind.BAKE
        val feeding = PhotoStore.PhotoKind.FEEDING
        assertThat(bake.dirName).isNotEqualTo(feeding.dirName)
        assertThat(bake.prefix).isNotEqualTo(feeding.prefix)
    }
}
