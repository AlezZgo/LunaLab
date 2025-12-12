package com.alezzgo.lunalab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alezzgo.lunalab.camera.CameraViewModel
import com.alezzgo.lunalab.core.camera.VideoRecordingState
import com.alezzgo.lunalab.ui.theme.LunaLabTheme

class ComposeCameraActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LunaLabTheme {
                val recordingState by viewModel.recordingState.collectAsState()
                val isRecording = recordingState is VideoRecordingState.Recording
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(Unit) {
                    viewModel.uiEvents.collect { msg ->
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        CameraXPreview(
                            modifier = Modifier.fillMaxSize(),
                            commands = viewModel.command,
                            recordingCommands = viewModel.recordingCommand,
                            onFrameFlow = { viewModel.observeFrames(it) },
                            onRecordingFlows = { state, events ->
                                viewModel.observeRecording(state, events)
                            }
                        )

                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Button(
                                onClick = { viewModel.startRecording() },
                                enabled = !isRecording,
                                modifier = Modifier.weight(1f)
                            ) { Text("Start") }

                            Button(
                                onClick = { viewModel.stopRecording() },
                                enabled = isRecording,
                                modifier = Modifier.weight(1f)
                            ) { Text("Stop") }
                        }
                    }
                }
            }
        }
    }
}

