package com.alezzgo.lunalab.core.camera

import android.content.Context
import android.util.AttributeSet
import android.util.Size
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val previewView = PreviewView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
    }

    private val _frameFlow = MutableSharedFlow<FrameData>(extraBufferCapacity = 1)
    val frameFlow: SharedFlow<FrameData> = _frameFlow

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var scope: CoroutineScope? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var autoStart = true

    // Буферы переиспользуются, избегаем аллокаций на каждом кадре
    private var yuvBuffer: ByteBuffer? = null
    private var lastBufferSize = 0

    // Кешируем CameraSelector
    private var cachedSelector: CameraSelector? = null
    private var cachedSelectorFacing = -1

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
        yuvBuffer = null
    }

    fun setLensFacing(facing: Int) {
        lensFacing = facing
    }

    fun setAutoStart(enabled: Boolean) {
        autoStart = enabled
    }

    fun bindCommands(commands: StateFlow<CameraCommand>) {
        scope?.launch {
            commands.collect { cmd ->
                when (cmd) {
                    CameraCommand.Start -> startCamera()
                    CameraCommand.Stop -> stopCamera()
                }
            }
        }
    }

    fun startCamera() {
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            cameraProvider = future.get()

            val preview = Preview.Builder()
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(analysisExecutor!!, ::processFrame) }

            cameraProvider?.run {
                unbindAll()
                bindToLifecycle(lifecycleOwner, getSelector(), preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun getSelector(): CameraSelector {
        if (cachedSelector == null || cachedSelectorFacing != lensFacing) {
            cachedSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
            cachedSelectorFacing = lensFacing
        }
        return cachedSelector!!
    }

    private fun processFrame(imageProxy: ImageProxy) {
        val size = calculateYuvSize(imageProxy)

        // Переиспользуем буфер если размер совпадает
        val buffer = if (lastBufferSize == size && yuvBuffer != null) {
            yuvBuffer!!.also { it.clear() }
        } else {
            ByteBuffer.allocateDirect(size).also {
                yuvBuffer = it
                lastBufferSize = size
            }
        }

        copyYuvToBuffer(imageProxy, buffer)
        buffer.flip()

        _frameFlow.tryEmit(
            FrameData(
                buffer = buffer,
                width = imageProxy.width,
                height = imageProxy.height,
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                timestampNanos = imageProxy.imageInfo.timestamp
            )
        )

        imageProxy.close()
    }

    private fun calculateYuvSize(imageProxy: ImageProxy): Int {
        val planes = imageProxy.planes
        var size = 0
        for (i in planes.indices) {
            size += planes[i].buffer.remaining()
        }
        return size
    }

    private fun copyYuvToBuffer(imageProxy: ImageProxy, dst: ByteBuffer) {
        val planes = imageProxy.planes
        for (i in planes.indices) {
            val src = planes[i].buffer
            dst.put(src)
        }
    }

    companion object {
        private val ANALYSIS_SIZE = Size(640, 480)
    }
}
