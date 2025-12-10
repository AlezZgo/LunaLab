package com.alezzgo.lunalab.core.camera

sealed interface CameraCommand {
    data object Start : CameraCommand
    data object Stop : CameraCommand
}

data class FrameData(
    val timestampNanos: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int
)

