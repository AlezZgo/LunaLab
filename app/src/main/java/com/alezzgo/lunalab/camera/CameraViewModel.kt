package com.alezzgo.lunalab.camera

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alezzgo.lunalab.core.camera.CameraCommand
import com.alezzgo.lunalab.core.camera.FrameData
import com.alezzgo.lunalab.core.camera.VideoRecordingCommand
import com.alezzgo.lunalab.core.camera.VideoRecordingEvent
import com.alezzgo.lunalab.core.camera.VideoRecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel : ViewModel() {

    private val _command = MutableStateFlow<CameraCommand>(CameraCommand.Start)
    val command: StateFlow<CameraCommand> = _command

    private val _recordingCommand = MutableStateFlow<VideoRecordingCommand>(VideoRecordingCommand.Stop)
    val recordingCommand: StateFlow<VideoRecordingCommand> = _recordingCommand

    private val _recordingState = MutableStateFlow<VideoRecordingState>(VideoRecordingState.Idle)
    val recordingState = _recordingState.asStateFlow()

    fun start() {
        _command.value = CameraCommand.Start
    }

    fun stop() {
        _command.value = CameraCommand.Stop
    }

    fun startRecording() {
        _recordingCommand.value = VideoRecordingCommand.Start
    }

    fun stopRecording() {
        _recordingCommand.value = VideoRecordingCommand.Stop
    }

    fun observeFrames(frameFlow: SharedFlow<FrameData>) {
        viewModelScope.launch {
            frameFlow.collect { frame ->
                Log.d("CameraViewModel", "Frame: ${frame.width}x${frame.height}, rotation=${frame.rotationDegrees}")
            }
        }
    }

    fun observeRecording(
        recordingState: StateFlow<VideoRecordingState>,
        recordingEvents: SharedFlow<VideoRecordingEvent>,
    ) {
        viewModelScope.launch {
            recordingState.collect { _recordingState.value = it }
        }
        viewModelScope.launch {
            recordingEvents.collect { event ->
                when (event) {
                    is VideoRecordingEvent.Started -> Log.d("CameraViewModel", "Recording started: ${event.outputUri}")
                    is VideoRecordingEvent.Finalized -> Log.d("CameraViewModel", "Recording finalized: ${event.outputUri}")
                    is VideoRecordingEvent.Error -> Log.e("CameraViewModel", "Recording error: ${event.outputUri}", event.error)
                }
            }
        }
    }
}

