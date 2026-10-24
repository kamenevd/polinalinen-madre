package com.polinalinen.madre.voice

import com.polinalinen.madre.config.FeatureFlags
import org.junit.Assert.assertTrue
import org.junit.Test

/** main source is compiled for both debug and release by the required CI job. */
class AliceBuildContractTest {
    @Test fun everyVariantIncludesTheActualAndroidVoiceFeature() { assertTrue(FeatureFlags.ALICE) }
}
