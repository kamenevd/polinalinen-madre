package com.polinalinen.madre.config

/** Android voice is included in both variants. Backend/OAuth require separate deployment consent. */
object FeatureFlags {
    const val RECIPE_EDIT = false
    const val ALICE = true
    // Combined 7.1 contract slice only. Parent enables after BOTH backends/native acceptance, not CI mocks.
    const val FAMILY_ROSTER_V2 = false
    // QA activation is an explicit build-time gate; release is always false until physical acceptance.
    const val YANDEX_ID = com.polinalinen.madre.BuildConfig.YANDEX_ID
}
