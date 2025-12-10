package com.alezzgo.lunalab.core.camera

import java.nio.ByteBuffer

sealed interface CameraCommand {
    data object Start : CameraCommand
    data object Stop : CameraCommand
}

class FrameData(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val timestampNanos: Long
) {
    companion object {
        const val YUV_420_888 = 35 // ImageFormat.YUV_420_888
    }
}
