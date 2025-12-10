package com.alezzgo.lunalab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import com.alezzgo.lunalab.camera.CameraViewModel
import com.alezzgo.lunalab.core.camera.CameraXView

class XmlCameraActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_xml_camera)

        val cameraView = findViewById<CameraXView>(R.id.cameraView)
        cameraView.bindCommands(viewModel.command)
        viewModel.observeFrames(cameraView.frameFlow)
    }
}

