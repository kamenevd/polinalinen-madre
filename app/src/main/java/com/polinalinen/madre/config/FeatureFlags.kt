package com.polinalinen.madre.config

/** Android voice is included in both variants. Backend/OAuth require separate deployment consent. */
object FeatureFlags {
    const val ALICE = true
    // Android account integration remains off until its real API/SDK acceptance.
    const val YANDEX_ID = false
}
