package com.alezzgo.lunalab.core.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Process
import android.view.Surface
import android.util.AttributeSet
import android.util.Range
import android.util.Size
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.io.File

class CameraXView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val previewView = PreviewView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
    }

    private val _frameFlow = MutableSharedFlow<FrameData>(extraBufferCapacity = 1)
    val frameFlow: SharedFlow<FrameData> = _frameFlow

    private val _recordingState = MutableStateFlow<VideoRecordingState>(VideoRecordingState.Idle)
    val recordingState = _recordingState.asStateFlow()

    private val _recordingEvents = MutableSharedFlow<VideoRecordingEvent>(extraBufferCapacity = 1)
    val recordingEvents: SharedFlow<VideoRecordingEvent> = _recordingEvents

    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private var scope: CoroutineScope? = null
    private var lensFacing = CameraSelector.LENS_FACING_FRONT
    private var autoStart = true

    private var previewUseCase: Preview? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var videoCaptureUseCase: VideoCapture<Recorder>? = null
    private var recorder: Recorder? = null

    private var isBound = false
    private var boundLensFacing = -1

    // Буферы переиспользуются, избегаем аллокаций на каждом кадре
    private var yuvBuffer: ByteBuffer? = null
    private var lastBufferSize = 0

    // Кешируем CameraSelector
    private var cachedSelector: CameraSelector? = null
    private var cachedSelectorFacing = -1

    // Опционально: попросить у камеры более высокий FPS (будет применено к preview + analysis)
    // По умолчанию просим "нормальный high-FPS" диапазон, который чаще поддерживается чем 60..60.
    private var targetFpsRange: Range<Int>? = Range(60, 60)

    // bindCommands() могут вызвать до attach — сохраняем ссылку и подпишемся позже.
    private var pendingCommands: StateFlow<CameraCommand>? = null
    private var commandJob: Job? = null

    private var pendingRecordingCommands: StateFlow<VideoRecordingCommand>? = null
    private var recordingCommandJob: Job? = null

    private var activeRecording: Recording? = null
    private var activeRecordingUri: Uri? = null
    private var pendingStartRecording = false

    init {
        addView(previewView)
        // Прогреваем инициализацию CameraX как можно раньше — это реально ускоряет "первый старт".
        ensureCameraProviderFuture()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureThreading()
        // Если bindCommands() вызывали до attach — начинаем собирать команды сейчас.
        pendingCommands?.let { bindCommands(it) }
        pendingRecordingCommands?.let { bindRecordingCommands(it) }
        if (autoStart) startCamera()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Если пишем видео — останавливаем запись при detach.
        stopRecording()
        stopCamera()
        analysisExecutor?.shutdown()
        analysisExecutor = null
        scope?.cancel()
        scope = null
        yuvBuffer = null
        previewUseCase = null
        analysisUseCase = null
        videoCaptureUseCase = null
        recorder = null
    }

    fun setLensFacing(facing: Int) {
        lensFacing = facing
        // Если камера уже запущена — ребиндим под новую камеру.
        if (isAttachedToWindow && isBound) startCamera(forceRebind = true)
    }

    fun setAutoStart(enabled: Boolean) {
        autoStart = enabled
    }

    fun bindCommands(commands: StateFlow<CameraCommand>) {
        pendingCommands = commands
        val localScope = scope ?: return
        commandJob?.cancel()
        commandJob = localScope.launch {
            commands.collect { cmd ->
                when (cmd) {
                    CameraCommand.Start -> startCamera()
                    CameraCommand.Stop -> stopCamera()
                }
            }
        }
    }

    fun bindRecordingCommands(commands: StateFlow<VideoRecordingCommand>) {
        pendingRecordingCommands = commands
        val localScope = scope ?: return
        recordingCommandJob?.cancel()
        recordingCommandJob = localScope.launch {
            commands.collect { cmd ->
                when (cmd) {
                    VideoRecordingCommand.Start -> requestStartRecording()
                    VideoRecordingCommand.Stop -> stopRecording()
                }
            }
        }
    }

    fun startCamera(forceRebind: Boolean = false) {
        val lifecycleOwner = findViewTreeLifecycleOwner() ?: return
        ensureThreading()
        val future = ensureCameraProviderFuture()

        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider

            val desiredFacing = lensFacing
            if (!forceRebind && isBound && boundLensFacing == desiredFacing) return@addListener

            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

            val previewBuilder = Preview.Builder()
                .setTargetRotation(rotation)
            applyTargetFps(previewBuilder)

            val preview = previewBuilder
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetResolution(ANALYSIS_SIZE)
                .setTargetRotation(rotation)
            applyTargetFps(analysisBuilder)

            val analysis = analysisBuilder
                .build()
                .also { it.setAnalyzer(analysisExecutor!!, ::processFrame) }

            val videoCapture = getOrCreateVideoCapture(rotation)

            previewUseCase = preview
            analysisUseCase = analysis
            videoCaptureUseCase = videoCapture

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, getSelector(), preview, analysis, videoCapture)
            }.onFailure {
                // Если девайс/камера не принимает наши request options (например FPS range),
                // не падаем — просто оставляем камеру остановленной.
                isBound = false
                return@addListener
            }

            isBound = true
            boundLensFacing = desiredFacing

            // Если запись запрашивали до того как камера успела забиндиться — стартуем сейчас.
            if (pendingStartRecording) {
                startRecordingNow()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        // Остановка камеры должна также останавливать активную запись.
        stopRecording()
        cameraProvider?.unbindAll()
        isBound = false
    }

    fun stopRecording() {
        pendingStartRecording = false
        activeRecording?.let { rec ->
            runCatching { rec.stop() }
            activeRecording = null
        }
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

    private fun requestStartRecording() {
        if (activeRecording != null) return
        pendingStartRecording = true
        // Гарантируем, что камера забиндится (если autoStart выключен и камера ещё не стартовала).
        startCamera()
        // Если уже забиндились — стартуем запись синхронно (без ожидания listener).
        if (isBound) startRecordingNow()
    }

    private fun startRecordingNow() {
        if (activeRecording != null) return
        val videoCapture = videoCaptureUseCase ?: return

        pendingStartRecording = false

        val outputFile = createVideoOutputFile()
        val outputUri = Uri.fromFile(outputFile)
        activeRecordingUri = outputUri

        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        val mainExecutor = ContextCompat.getMainExecutor(context)

        _recordingState.value = VideoRecordingState.Recording(outputUri)

        activeRecording = videoCapture.output
            .prepareRecording(context, outputOptions)
            .start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        _recordingEvents.tryEmit(VideoRecordingEvent.Started(outputUri))
                    }

                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            val t = event.cause
                                ?: IllegalStateException("Video finalize error=${event.error}")
                            _recordingEvents.tryEmit(VideoRecordingEvent.Error(outputUri, t))
                        } else {
                            _recordingEvents.tryEmit(VideoRecordingEvent.Finalized(outputUri))
                        }

                        activeRecording = null
                        activeRecordingUri = null
                        _recordingState.value = VideoRecordingState.Idle
                    }

                    else -> Unit
                }
            }
    }

    private fun getOrCreateVideoCapture(rotation: Int): VideoCapture<Recorder> {
        val localRecorder = recorder ?: Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.LOWEST))
            .build()
            .also { recorder = it }

        // В alpha API может быть either Builder() либо withOutput(). Используем withOutput и выставляем rotation.
        return VideoCapture.withOutput(localRecorder).also { it.targetRotation = rotation }
    }

    private fun createVideoOutputFile(): File {
        val dir = File(context.filesDir, "video").also { it.mkdirs() }
        val ts = LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("ddMMyyyy_HHmmss", Locale.US)
        )
        return File(dir, "VID_${ts}.mp4")
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            // Ключевой win для FPS/CPU: если никто не слушает — не копируем кадр вообще.
            if (_frameFlow.subscriptionCount.value == 0) return

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

            // ВАЖНО: даже при переиспользовании backing-buffer, отдаем независимые позиции/лимиты.
            // (Данные всё ещё будут перезаписываться следующими кадрами — если нужно "владение",
            // сделаем пул+release(), но это уже изменение контракта.)
            val out = buffer.asReadOnlyBuffer()

            _frameFlow.tryEmit(
                FrameData(
                    buffer = out,
                    width = imageProxy.width,
                    height = imageProxy.height,
                    rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                    timestampNanos = imageProxy.imageInfo.timestamp
                )
            )
        } finally {
            imageProxy.close()
        }
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

    private fun ensureCameraProviderFuture(): ListenableFuture<ProcessCameraProvider> {
        return cameraProviderFuture ?: ProcessCameraProvider.getInstance(context).also {
            cameraProviderFuture = it
        }
    }

    private fun ensureThreading() {
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
        if (analysisExecutor == null) {
            analysisExecutor = Executors.newSingleThreadExecutor { r ->
                Thread {
                    // Чуть приоритезируем анализатор, чтобы держать FPS стабильнее.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
                    r.run()
                }.apply { name = "CameraXAnalysis" }
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyTargetFps(builder: Preview.Builder) {
        val range = targetFpsRange ?: return
        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyTargetFps(builder: ImageAnalysis.Builder) {
        val range = targetFpsRange ?: return
        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
    }

    companion object {
        private val ANALYSIS_SIZE = Size(640, 480)
    }
}
