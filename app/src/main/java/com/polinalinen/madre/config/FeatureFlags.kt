package com.polinalinen.madre.config

/** Android voice is included in both variants. Backend/OAuth require separate deployment consent. */
object FeatureFlags {
    const val RECIPE_EDIT = false
    const val ALICE = true
    // QA candidate only, not backend readiness: production/debug retain the legacy lane.
    // Alice roster/control/auth only; family invitations and lists have a separate gate.
    const val FAMILY_LINK_UI = com.polinalinen.madre.BuildConfig.FAMILY_LINK_UI
    const val FAMILY_ROSTER_V2 = com.polinalinen.madre.BuildConfig.FAMILY_ROSTER_V2
    const val V2_ACCOUNT = FAMILY_LINK_UI || FAMILY_ROSTER_V2
    // QA activation is an explicit build-time gate; release is always false until physical acceptance.
    const val YANDEX_ID = com.polinalinen.madre.BuildConfig.YANDEX_ID
}
