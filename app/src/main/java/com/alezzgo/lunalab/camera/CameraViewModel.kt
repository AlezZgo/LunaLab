package com.alezzgo.lunalab.camera

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CameraViewModel : ViewModel() {

    private val _command = MutableStateFlow<CameraCommand>(CameraCommand.Start)
    val command: StateFlow<CameraCommand> = _command

    fun start() {
        _command.value = CameraCommand.Start
    }

    fun stop() {
        _command.value = CameraCommand.Stop
    }

    fun observeFrames(frameFlow: SharedFlow<FrameData>) {
        viewModelScope.launch {
            frameFlow.collect { frame ->
                Log.d("CameraViewModel", "Frame: ${frame.width}x${frame.height}, rotation=${frame.rotationDegrees}")
            }
        }
    }
}

