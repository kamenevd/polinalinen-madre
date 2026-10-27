package com.polinalinen.madre.model

/** Local book settings only. The privacy default matches Android's existing PRIVATE notification default. */
data class BookDisplaySettings(
    val keepScreenOn: Boolean = true,
    val lockScreenProgress: Boolean = false,
)
