package com.polinalinen.madre.ui.photo

import androidx.compose.runtime.saveable.Saver

/**
 * One-attempt cancel dedupe for photo attachment flows.
 *
 * Some launchers and sheets can emit duplicate dismiss/cancel callbacks for the
 * same user action. This state machine guarantees we notify cancellation once
 * per explicit "open photo road" attempt.
 */
data class PhotoRoad(
    private val cancelReported: Boolean = false,
) {
    companion object {
        val Saver: Saver<PhotoRoad, Boolean> = Saver(
            save = { it.cancelReported },
            restore = { PhotoRoad(cancelReported = it) },
        )
    }

    fun begin(): PhotoRoad = PhotoRoad(cancelReported = false)

    fun attached(): PhotoRoad = PhotoRoad(cancelReported = false)

    fun cancel(): CancelResult =
        if (cancelReported) {
            CancelResult(next = this, shouldNotify = false)
        } else {
            CancelResult(next = PhotoRoad(cancelReported = true), shouldNotify = true)
        }

    data class CancelResult(
        val next: PhotoRoad,
        val shouldNotify: Boolean,
    )
}
