package com.drivingassist.glass

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CameraPreview"

/**
 * @param targetResolution optional analysis size; the preview gets the same aspect ratio (so the
 *   analysed frame has the preview's field of view) at CameraX's preview size. Null (default) keeps
 *   CameraX's defaults. The laptop perception source asks for 1280x720 (PERCEPTION_INTEGRATION.md).
 *
 * With an analyzer, target rotation follows the display, including 180-degree flips between
 * landscape and reverse landscape, which cause no configuration change (DisplayListener, as the
 * CameraX docs advise), so ImageProxy.rotationDegrees stays right.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    frameAnalyzer: ImageAnalysis.Analyzer? = null,
    frameGeometry: FrameGeometry? = null,
    targetResolution: Size? = null,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) {
            Toast.makeText(context, "Camera permission is required for Glass Mode", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasPermission) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Camera access is required for the live feed.")
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Allow camera")
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(lifecycleOwner, frameAnalyzer, targetResolution) {
        val disposed = AtomicBoolean(false)
        val resolutionSelector = targetResolution?.let(::resolutionSelectorFor)
        val previewSelector = targetResolution?.let(::aspectRatioSelectorFor)
        var boundPreview: Preview? = null
        var boundAnalysis: ImageAnalysis? = null
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                val display = previewView.display ?: return
                if (display.displayId != displayId) return
                boundPreview?.targetRotation = display.rotation
                boundAnalysis?.targetRotation = display.rotation
            }
        }
        // Only with an analyzer: its rotationDegrees must follow the display (PreviewView rotates the preview itself).
        if (frameAnalyzer != null) displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        val cameraExecutor = if (frameAnalyzer != null) Executors.newSingleThreadExecutor() else null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (disposed.get()) return@addListener
                try {
                    val provider = future.get()
                    val selector = when {
                        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                            CameraSelector.DEFAULT_BACK_CAMERA
                        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        else -> {
                            Log.e(TAG, "No camera available")
                            return@addListener
                        }
                    }
                    val preview = Preview.Builder()
                        .apply { previewSelector?.let { setResolutionSelector(it) } }
                        .build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    boundPreview = preview
                    val rotation = previewView.display?.rotation
                    if (rotation != null) {
                        preview.targetRotation = rotation
                    }
                    provider.unbindAll()
                    if (frameAnalyzer != null && cameraExecutor != null) {
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .apply { resolutionSelector?.let { setResolutionSelector(it) } }
                            .build()
                        if (rotation != null) {
                            analysis.targetRotation = rotation
                        }
                        boundAnalysis = analysis
                        analysis.setAnalyzer(cameraExecutor, frameAnalyzer)
                        provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                    } else {
                        provider.bindToLifecycle(lifecycleOwner, selector, preview)
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Camera bind failed", error)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            disposed.set(true)
            if (frameAnalyzer != null) displayManager?.unregisterDisplayListener(displayListener)
            cameraExecutor?.shutdown()
            if (future.isDone) {
                runCatching { future.get().unbindAll() }
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                frameGeometry?.viewWidth = coordinates.size.width.toFloat()
                frameGeometry?.viewHeight = coordinates.size.height.toFloat()
            },
    )
}

/** Closest size to [target] with the target's aspect ratio (16:9 or 4:3), falling back to CameraX's choice. */
private fun resolutionSelectorFor(target: Size): ResolutionSelector {
    return ResolutionSelector.Builder()
        .setAspectRatioStrategy(aspectRatioStrategyFor(target))
        .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build()
}

/** Only the target's aspect ratio (same field of view as the analysis), at CameraX's default preview size. */
private fun aspectRatioSelectorFor(target: Size): ResolutionSelector =
    ResolutionSelector.Builder().setAspectRatioStrategy(aspectRatioStrategyFor(target)).build()

private fun aspectRatioStrategyFor(target: Size): AspectRatioStrategy {
    val aspect = target.width.toFloat() / target.height.coerceAtLeast(1)
    return if (kotlin.math.abs(aspect - 16f / 9f) < kotlin.math.abs(aspect - 4f / 3f)) {
        AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    } else {
        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
    }
}
