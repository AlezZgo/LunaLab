package com.alezzgo.lunalab.camera

import androidx.camera.core.CameraSelector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

@Composable
fun CameraXPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    autoStart: Boolean = true,
    commands: StateFlow<CameraCommand>? = null,
    onFrameFlow: ((SharedFlow<FrameData>) -> Unit)? = null
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
        commands?.let { cameraView.bindCommands(it) }
        onDispose { }
    }

    AndroidView(
        factory = { cameraView },
        modifier = modifier
    )
}

