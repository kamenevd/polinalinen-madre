package com.polinalinen.madre.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PhotoStoreOrphanTest {
    @Test
    fun sweepDeletesUnreferencedFilesOnly() {
        val context = RuntimeEnvironment.getApplication()
        val keepRel = PhotoStore.relativePath(PhotoStore.PhotoKind.BAKE, 1L)
        val keep = PhotoStore.resolve(context, keepRel)
        keep.parentFile?.mkdirs()
        keep.writeText("keep")
        val orphan = File(keep.parentFile, "bake_orphan_x.jpg")
        orphan.writeText("gone")

        PhotoStore.sweepOrphans(context, setOf(keepRel))

        assertThat(keep.exists()).isTrue()
        assertThat(orphan.exists()).isFalse()
    }
}
