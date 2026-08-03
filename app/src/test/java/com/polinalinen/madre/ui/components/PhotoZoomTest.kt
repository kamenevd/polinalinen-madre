package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 12, полноэкранный просмотр фотокарточки: щипок не должен ни вывернуть
 * снимок наизнанку, ни дать утащить его за пределы экрана, откуда его уже не
 * вернуть пальцем.
 */
class PhotoZoomTest {

    @Test
    fun `scale never goes below the original or above the ceiling`() {
        assertThat(PhotoZoom.clampScale(0.1f)).isEqualTo(PhotoZoom.MIN_SCALE)
        assertThat(PhotoZoom.clampScale(-3f)).isEqualTo(PhotoZoom.MIN_SCALE)
        assertThat(PhotoZoom.clampScale(100f)).isEqualTo(PhotoZoom.MAX_SCALE)
        assertThat(PhotoZoom.clampScale(2f)).isEqualTo(2f)
    }

    @Test
    fun `an un-zoomed photo cannot be dragged off centre`() {
        assertThat(PhotoZoom.clampOffset(500f, scale = 1f, viewportPx = 1000f)).isEqualTo(0f)
        assertThat(PhotoZoom.clampOffset(-500f, scale = 1f, viewportPx = 1000f)).isEqualTo(0f)
    }

    @Test
    fun `a zoomed photo moves only within the overhanging margins`() {
        // Масштаб 2 на экране 1000px — за край наехало по 500px с каждой стороны.
        assertThat(PhotoZoom.clampOffset(200f, scale = 2f, viewportPx = 1000f)).isEqualTo(200f)
        assertThat(PhotoZoom.clampOffset(9_000f, scale = 2f, viewportPx = 1000f)).isEqualTo(500f)
        assertThat(PhotoZoom.clampOffset(-9_000f, scale = 2f, viewportPx = 1000f)).isEqualTo(-500f)
    }
}
