package com.livecaption.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Service status, observed by the settings screen. */
sealed interface CaptionStatus {
    data object Idle : CaptionStatus
    data class Preparing(val message: String) : CaptionStatus
    data object Running : CaptionStatus
    data class Error(val message: String) : CaptionStatus
}

object CaptionState {
    val status = MutableStateFlow<CaptionStatus>(CaptionStatus.Idle)

    /** The floating captions ignore touches (see [CaptionOverlay.setLocked]). */
    val locked = MutableStateFlow(false)
}
