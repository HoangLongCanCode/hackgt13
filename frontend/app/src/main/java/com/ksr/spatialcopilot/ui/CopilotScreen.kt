package com.ksr.spatialcopilot.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ksr.spatialcopilot.CopilotUi
import com.ksr.spatialcopilot.CopilotViewModel
import com.ksr.spatialcopilot.camera.DrivingCamera
import com.ksr.spatialcopilot.model.ArrowHeading
import com.ksr.spatialcopilot.perception.LinkState

private val Mint = Color(0xFF7DFFC3)
private val Ink = Color(0xCC101614)

@Composable
fun CopilotScreen(viewModel: CopilotViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var cameraGranted by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> cameraGranted = granted }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        cameraGranted = granted
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (cameraGranted) {
                DrivingCamera(
                    analysisEnabled = viewModel::canSendFrame,
                    onFrame = viewModel::onCameraFrame,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            SpatialArEngine(instruction = ui.instruction, modifier = Modifier.fillMaxSize())
            Chrome(
                ui = ui,
                cameraGranted = cameraGranted,
                onServerClick = { showServer = true },
            )
            if (showServer) {
                ServerDialog(
                    initialUrl = ui.serverUrl,
                    onDismiss = { showServer = false },
                    onConnect = { url ->
                        val ok = viewModel.updateServerUrl(url)
                        if (ok) showServer = false
                        ok
                    },
                )
            }
        }
    }
}

@Composable
private fun Chrome(
    ui: CopilotUi,
    cameraGranted: Boolean,
    onServerClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        StatusChip(
            label = statusLabel(ui.link, ui.instruction.source),
            detail = ui.linkDetail,
            modifier = Modifier.align(Alignment.TopStart).clickable(onClick = onServerClick),
        )
        ui.instruction.navigation.exit?.let { exit ->
            ExitHud(
                label = exit.label,
                distance = exit.distanceLabel,
                laneHint = laneHint(ui),
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        if (!cameraGranted) {
            Text(
                text = "Allow the camera, then point it at the driving video.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        val audio = ui.instruction.navigation.audio
        if (audio.isNotBlank()) {
            Text(
                text = audio,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Ink, RoundedCornerShape(24.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Ink, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = label, color = Mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (detail.isNotBlank()) {
            Text(text = detail, color = Color(0xFFFFC56B), fontSize = 12.sp)
        } else {
            Text(text = "tap to set server", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ExitHud(
    label: String,
    distance: String,
    laneHint: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(1.dp, Mint.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.46f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = label,
            color = Mint,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Text(
            text = distance,
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
        )
        if (laneHint != null) {
            Text(text = laneHint, color = Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
        }
    }
}

@Composable
private fun ServerDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onConnect: (String) -> Boolean,
) {
    var draft by remember(initialUrl) { mutableStateOf(initialUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Perception server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "USB: adb reverse tcp:8765 tcp:8765, then use 127.0.0.1. On Wi-Fi, use the laptop's LAN address.",
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("WebSocket URL") },
                )
                if (error != null) {
                    Text(error!!, color = Color(0xFFFF8A80), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!onConnect(draft)) error = "Use a ws:// or wss:// URL"
            }) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun statusLabel(link: LinkState, source: String): String = when (link) {
    LinkState.TAKEN_OVER -> "TAKEN OVER"
    LinkState.CONNECTING -> if (source == "demo") "DEMO · connecting" else "CONNECTING"
    LinkState.RECONNECTING -> if (source == "demo") "DEMO · reconnecting" else "RECONNECTING"
    LinkState.LIVE -> when (source) {
        "demo" -> "DEMO"
        "live-sim-nav" -> "LIVE · sim nav"
        else -> "LIVE"
    }
    LinkState.DEMO -> "DEMO"
}

private fun laneHint(ui: CopilotUi): String? {
    val lane = ui.instruction.lanes.firstOrNull { it.arrow.highlighted } ?: return null
    return when (lane.arrow.heading) {
        ArrowHeading.RIGHT -> "RIGHT LANE"
        ArrowHeading.LEFT -> "LEFT LANE"
        ArrowHeading.STRAIGHT -> "LANE ${lane.index}"
    }
}
