package com.polinalinen.madre.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LegacyPrefsShelfShareTest {

    @Test
    fun `obsolete keys include only retired shelf share key`() {
        val keys = setOf(
            "shelf_share_mode",
            "my_name",
            "calm_mode",
            "coffee_ring_rye",
            "bake_record_session_42",
        )

        val obsolete = LegacyPrefs.obsoleteKeys(keys)

        assertThat(obsolete).contains("shelf_share_mode")
        assertThat(obsolete).doesNotContain("my_name")
        assertThat(obsolete).doesNotContain("calm_mode")
        assertThat(obsolete).doesNotContain("coffee_ring_rye")
        assertThat(obsolete).doesNotContain("bake_record_session_42")
    }
}
