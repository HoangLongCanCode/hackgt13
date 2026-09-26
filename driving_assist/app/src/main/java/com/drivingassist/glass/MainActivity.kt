package com.drivingassist.glass

import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            DrivingAssistTheme {
                DrivingAssistApp()
            }
        }
    }
}

@Composable
private fun DrivingAssistTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF3DFFB0),
            onPrimary = Color(0xFF003828),
            background = Color.Black,
            surface = Color(0xFF10241C),
        ),
        content = content,
    )
}

@Composable
private fun DrivingAssistApp(viewModel: MockDataViewModel = viewModel()) {
    val visionData by viewModel.visionData.collectAsStateWithLifecycle()
    val routeState by viewModel.routeState.collectAsStateWithLifecycle()
    var isDebugMode by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            frameAnalyzer = viewModel.frameAnalyzer,
            frameGeometry = viewModel.frameGeometry,
        )
        AROverlay(
            visionData = visionData,
            routeState = routeState,
            isDebugMode = isDebugMode,
            onDebugModeChange = { isDebugMode = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
