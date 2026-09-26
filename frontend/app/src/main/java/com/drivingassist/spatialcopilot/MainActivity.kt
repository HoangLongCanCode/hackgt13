package com.drivingassist.spatialcopilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.drivingassist.spatialcopilot.ui.CopilotScreen

class MainActivity : ComponentActivity() {
    private val viewModel: CopilotViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        if (savedInstanceState == null) viewModel.onLaunchIntent(intent)
        setContent { CopilotScreen(viewModel) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.voice.hostVisible = true
        viewModel.session.value.startLocation() // no-op without permission or outside LIVE
    }

    override fun onStop() {
        viewModel.voice.hostVisible = false
        viewModel.session.value.stopLocation()
        super.onStop()
    }
}
