package com.polinalinen.madre.config

/** Android voice is included in both variants. Backend/OAuth require separate deployment consent. */
object FeatureFlags {
    const val ALICE = true
    // QA activation is an explicit build-time gate; release is always false until physical acceptance.
    const val YANDEX_ID = com.polinalinen.madre.BuildConfig.YANDEX_ID
}
