package com.alezzgo.lunalab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.alezzgo.lunalab.camera.CameraViewModel
import com.alezzgo.lunalab.ui.theme.LunaLabTheme

class ComposeCameraActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LunaLabTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CameraXPreview(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        commands = viewModel.command,
                        onFrameFlow = { viewModel.observeFrames(it) }
                    )
                }
            }
        }
    }
}

