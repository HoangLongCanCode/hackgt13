package com.drivingassist.glass.perception

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small corner chip: source (LIVE / SIM, "(saved)" when saved launch values are in use), result
 * fps, capture->result latency (LIVE) or how early results arrive (SIM), DISCONNECTED / STALE /
 * TAKEN OVER, navigation state, and the top Driving Context alert (e.g. "CLOSE 6 m") or the current
 * problem. MainActivity composes it only for LIVE / SIM, so MOCK looks exactly like Tom's app.
 */
@Composable
fun BridgeStatusChip(runtime: PerceptionRuntime?, modifier: Modifier = Modifier) {
    val flow: StateFlow<BridgeStatusUi> = remember(runtime) { runtime?.status ?: MutableStateFlow(BridgeStatusUi.MOCK) }
    val status by flow.collectAsStateWithLifecycle()
    val color = when (status.level) {
        BridgeStatusUi.Level.OK -> Color(0xFF3DFFB0)
        BridgeStatusUi.Level.WARN -> Color(0xFFFFB020)
        BridgeStatusUi.Level.ERROR -> Color(0xFFFF5A4F)
    }
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(status.line1, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        status.line2?.let { Text(it, color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
    }
}

/**
 * Everything the LIVE / SIM host needs besides the chip: keeps the screen on (a mounted tablet must
 * not time out mid-drive: CameraX would unbind and the uplink stop), reports the activity's
 * visibility to the runtime (loops idle and GPS pauses while stopped), and asks for location.
 */
@Composable
fun PerceptionHostEffects(runtime: PerceptionRuntime) {
    KeepScreenOn()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, runtime) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> runtime.onHostStarted()
                Lifecycle.Event.ON_STOP -> runtime.onHostStopped()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LocationPermissionRequest(runtime)
}

/** FLAG_KEEP_SCREEN_ON while this composable is shown (restored when it leaves). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}

/**
 * LIVE + navigation only: asks for location once the camera permission is settled (Android shows
 * one permission dialog at a time): granted, or denied / never asked (the activity has been in the
 * foreground for a moment with no permission dialog over it). Then GPS feeds the laptop. Denial is
 * fine: the route simply stays "waiting" and perception keeps working.
 */
@Composable
fun LocationPermissionRequest(runtime: PerceptionRuntime) {
    if (!runtime.wantsLocation) return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) runtime.startLocationUpdates() else runtime.problem = "location denied: no live navigation"
    }
    LaunchedEffect(runtime) {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val t0 = SystemClock.elapsedRealtime()
        var resumedSince: Long? = null
        while (!granted(Manifest.permission.CAMERA)) {
            // A permission dialog pauses the activity; resumed for 1.5 s (after the first 2 s) = no dialog is up.
            val now = SystemClock.elapsedRealtime()
            resumedSince = if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resumedSince ?: now else null
            if (now - t0 > 2_000 && resumedSince != null && now - resumedSince > 1_500) break
            delay(250)
        }
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            runtime.startLocationUpdates()
        } else {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}
