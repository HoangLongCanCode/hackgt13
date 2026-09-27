package com.drivingassist.spatialcopilot.ui

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.drivingassist.spatialcopilot.session.SimPlayback

/**
 * SIM mode background: the clip full screen in place of the camera preview. RESIZE_MODE_ZOOM is the
 * PlayerView equivalent of PreviewView FILL_CENTER (scale to cover, crop the centre), which is what the
 * AR mapping assumes. Pauses with the activity.
 */
@OptIn(UnstableApi::class)
@Composable
fun SimVideoBackground(sim: SimPlayback, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sim) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> sim.onHostPaused()
                Lifecycle.Event.ON_RESUME -> sim.onHostResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    keepScreenOn = true
                    player = sim.player
                }
            },
            update = { if (it.player !== sim.player) it.player = sim.player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (sim.videoFile == null) {
            Text(
                text = "SIM clip missing: adb push ${sim.videoId}.mov /sdcard/Android/data/com.drivingassist.spatialcopilot/files/sim/",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
    }
}
