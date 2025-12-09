package com.alezzgo.lunalab.camera

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val previewView = PreviewView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private val _frameFlow = MutableSharedFlow<FrameData>(extraBufferCapacity = 1)
    val frameFlow: SharedFlow<FrameData> = _frameFlow

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var scope: CoroutineScope? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var autoStart = true

    init {
        addView(previewView)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        analysisExecutor = Executors.newSingleThreadExecutor()
        if (autoStart) startCamera()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCamera()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        scope?.cancel()
        scope = null
    }

    fun setLensFacing(facing: Int) {
        lensFacing = facing
    }

    fun setAutoStart(enabled: Boolean) {
        autoStart = enabled
    }

    fun bindCommands(commands: StateFlow<CameraCommand>) {
        scope?.launch {
            commands.collect { command ->
                when (command) {
                    CameraCommand.Start -> startCamera()
                    CameraCommand.Stop -> stopCamera()
                }
            }
        }
    }

    fun startCamera() {
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor!!) { imageProxy ->
                        _frameFlow.tryEmit(
                            FrameData(
                                timestampNanos = imageProxy.imageInfo.timestamp,
                                width = imageProxy.width,
                                height = imageProxy.height,
                                rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            )
                        )
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
    }
}

