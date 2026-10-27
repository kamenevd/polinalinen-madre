package com.polinalinen.madre.config

import com.polinalinen.madre.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** Current explicit parent approval enables QA only; ordinary identity stays disabled. */
class BuildVariantAccountFlagTest {
    @Test fun approvedAccountGateFollowsTheActualBuildIdentity() {
        assertEquals(BuildConfig.DEBUG, BuildConfig.YANDEX_ID)
        assertEquals(BuildConfig.DEBUG, BuildConfig.APPLICATION_ID.endsWith(".qa"))
        assertEquals(BuildConfig.YANDEX_ID, FeatureFlags.YANDEX_ID)
    }
}
