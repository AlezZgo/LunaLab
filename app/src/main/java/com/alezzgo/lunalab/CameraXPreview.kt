package com.alezzgo.lunalab

import androidx.camera.core.CameraSelector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.alezzgo.lunalab.core.camera.CameraCommand
import com.alezzgo.lunalab.core.camera.CameraXView
import com.alezzgo.lunalab.core.camera.FrameData
import com.alezzgo.lunalab.core.camera.VideoRecordingCommand
import com.alezzgo.lunalab.core.camera.VideoRecordingEvent
import com.alezzgo.lunalab.core.camera.VideoRecordingState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun CameraXPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    autoStart: Boolean = true,
    commands: StateFlow<CameraCommand>? = null,
    recordingCommands: StateFlow<VideoRecordingCommand>? = null,
    onFrameFlow: ((SharedFlow<FrameData>) -> Unit)? = null,
    onRecordingFlows: ((StateFlow<VideoRecordingState>, SharedFlow<VideoRecordingEvent>) -> Unit)? = null,
) {
    val context = LocalContext.current
    val cameraView = remember {
        CameraXView(context).apply {
            setLensFacing(lensFacing)
            setAutoStart(autoStart)
        }
    }

    DisposableEffect(cameraView) {
        onFrameFlow?.invoke(cameraView.frameFlow)
        onRecordingFlows?.invoke(cameraView.recordingState, cameraView.recordingEvents)
        commands?.let { cameraView.bindCommands(it) }
        recordingCommands?.let { cameraView.bindRecordingCommands(it) }
        onDispose { }
    }

    AndroidView(
        factory = { cameraView },
        modifier = modifier
    )
}

