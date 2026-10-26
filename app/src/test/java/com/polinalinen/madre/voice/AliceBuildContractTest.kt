package com.polinalinen.madre.voice

import com.polinalinen.madre.BuildConfig
import com.polinalinen.madre.config.FeatureFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Debug units retain the legacy voice lane; release/QA defaults have source contracts. */
class AliceBuildContractTest {
    @Test fun debugIncludesTheActualAndroidVoiceFeature() {
        assertTrue(FeatureFlags.ALICE)
        assertEquals(BuildConfig.ALICE, FeatureFlags.ALICE)
    }
}
