package com.alezzgo.lunalab.core.camera

import android.net.Uri

sealed interface VideoRecordingCommand {
    data object Start : VideoRecordingCommand
    data object Stop : VideoRecordingCommand
}

sealed interface VideoRecordingState {
    data object Idle : VideoRecordingState
    data class Recording(val outputUri: Uri) : VideoRecordingState
}

sealed interface VideoRecordingEvent {
    data class Started(val outputUri: Uri) : VideoRecordingEvent
    data class Finalized(val outputUri: Uri) : VideoRecordingEvent
    data class Error(val outputUri: Uri?, val error: Throwable) : VideoRecordingEvent
}


