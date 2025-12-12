package com.alezzgo.lunalab

import android.os.Bundle
import android.widget.Toast
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.alezzgo.lunalab.camera.CameraViewModel
import com.alezzgo.lunalab.core.camera.CameraXView
import com.alezzgo.lunalab.core.camera.VideoRecordingState
import kotlinx.coroutines.launch

class XmlCameraActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xml_camera)

        val cameraView = findViewById<CameraXView>(R.id.cameraView)
        cameraView.bindCommands(viewModel.command)
        cameraView.bindRecordingCommands(viewModel.recordingCommand)
        viewModel.observeFrames(cameraView.frameFlow)
        viewModel.observeRecording(cameraView.recordingState, cameraView.recordingEvents)

        val btnStart = findViewById<Button>(R.id.btnStartRecording)
        val btnStop = findViewById<Button>(R.id.btnStopRecording)

        btnStart.setOnClickListener { viewModel.startRecording() }
        btnStop.setOnClickListener { viewModel.stopRecording() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                cameraView.recordingState.collect { state ->
                    val isRecording = state is VideoRecordingState.Recording
                    btnStart.isEnabled = !isRecording
                    btnStop.isEnabled = isRecording
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiEvents.collect { msg ->
                    Toast.makeText(this@XmlCameraActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

