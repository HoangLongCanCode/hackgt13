package com.drivingassist.spatialcopilot.camera

import android.util.Log
import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Landscape camera preview (FILL_CENTER) plus a 16:9 ~960x540 analysis stream for the LIVE uplink.
 * Both use 16:9, so the analysed image has the preview's field of view and server coordinates map onto
 * the preview with the FILL_CENTER rule. The analyzer runs on its own thread with
 * `STRATEGY_KEEP_ONLY_LATEST` (the camera never waits for the network); [analyzer] must close the image.
 * Preview uses a TextureView so the Compose overlay is not punched through.
 */
@Composable
fun DrivingCamera(
    analyzer: ((ImageProxy) -> Unit)?,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analyze = rememberUpdatedState(analyzer)
    val error = rememberUpdatedState(onError)
    val executor = remember { Executors.newSingleThreadExecutor { r -> Thread(r, "camera-analysis") } }
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val disposed = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            disposed.set(true)
            providerRef.get()?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val future = ProcessCameraProvider.getInstance(viewContext)
            future.addListener(
                {
                    if (disposed.get()) return@addListener // left composition before the provider was ready
                    val provider = runCatching { future.get() }.getOrElse {
                        error.value("Camera unavailable: ${it.message}")
                        return@addListener
                    }
                    providerRef.set(provider)
                    bindCamera(provider, previewView, lifecycleOwner, executor, { image ->
                        val a = analyze.value
                        if (a != null) a(image) else image.close()
                    }, { error.value(it) })
                },
                ContextCompat.getMainExecutor(viewContext),
            )
            previewView
        },
    )
}

private fun bindCamera(
    provider: ProcessCameraProvider,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    executor: ExecutorService,
    analyzer: (ImageProxy) -> Unit,
    onError: (String) -> Unit,
) {
    val preview = Preview.Builder()
        .setResolutionSelector(selector(Size(1280, 720)))
        .build()
        .also { it.surfaceProvider = previewView.surfaceProvider }
    val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(selector(Size(960, 540)))
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()
    analysis.setAnalyzer(executor) { image ->
        try {
            analyzer(image) // closes the image
        } catch (t: Throwable) {
            image.close()
        }
    }
    previewView.post {
        val rotation = previewView.display?.rotation ?: return@post
        preview.targetRotation = rotation
        analysis.targetRotation = rotation
    }
    try {
        val selector = when {
            provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
            provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> { onError("No camera on this device"); return }
        }
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
    } catch (e: Exception) {
        Log.e("DrivingCamera", "bind failed", e)
        onError("Camera unavailable: ${e.message}")
        provider.unbindAll()
    }
}

private fun selector(size: Size): ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
    .build()
