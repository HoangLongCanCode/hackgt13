package com.ksr.spatialcopilot.camera

import android.util.Size
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Landscape camera preview aimed at a monitor playing a POV drive.
 * Analysis frames are JPEG'd only while [analysisEnabled] is true.
 * Preview uses a TextureView so the Compose overlay is not punched through.
 */
@Composable
fun DrivingCamera(
    analysisEnabled: () -> Boolean,
    onFrame: (jpeg: ByteArray, rotationDegrees: Int, bufferWidth: Int, bufferHeight: Int, timestampNs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val gate = rememberUpdatedState(analysisEnabled)
    val frameCallback = rememberUpdatedState(onFrame)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val providerRef = remember { AtomicReference<androidx.camera.lifecycle.ProcessCameraProvider?>(null) }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            providerRef.get()?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val previewView = PreviewView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val future = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(viewContext)
            future.addListener(
                {
                    val provider = future.get()
                    providerRef.set(provider)
                    bindCamera(
                        provider = provider,
                        previewView = previewView,
                        lifecycleOwner = lifecycleOwner,
                        executor = executor,
                        analysisEnabled = { gate.value() },
                        onFrame = { jpeg, rotation, width, height, timestampNs ->
                            frameCallback.value(jpeg, rotation, width, height, timestampNs)
                        },
                    )
                },
                ContextCompat.getMainExecutor(viewContext),
            )
            previewView
        },
    )
}

private fun bindCamera(
    provider: androidx.camera.lifecycle.ProcessCameraProvider,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    executor: java.util.concurrent.ExecutorService,
    analysisEnabled: () -> Boolean,
    onFrame: (ByteArray, Int, Int, Int, Long) -> Unit,
) {
    val previewSelector = selector(Size(1280, 720))
    val analysisSelector = selector(Size(960, 540))
    val preview = Preview.Builder()
        .setResolutionSelector(previewSelector)
        .build()
        .also { it.surfaceProvider = previewView.surfaceProvider }
    val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(analysisSelector)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        .build()
    analysis.setAnalyzer(executor) { image ->
        try {
            if (!analysisEnabled()) return@setAnalyzer
            val jpeg = YuvJpeg.encode(image, quality = 80)
            onFrame(
                jpeg,
                image.imageInfo.rotationDegrees,
                image.width,
                image.height,
                image.imageInfo.timestamp,
            )
        } catch (_: Exception) {
            // Drop a bad frame. The next one is already queued as KEEP_ONLY_LATEST.
        } finally {
            image.close()
        }
    }
    previewView.post {
        val rotation = previewView.display?.rotation ?: return@post
        preview.targetRotation = rotation
        analysis.targetRotation = rotation
    }
    val selector = when {
        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
        else -> return
    }
    try {
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
    } catch (_: Exception) {
        provider.unbindAll()
    }
}

private fun selector(size: Size): ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
    )
    .build()
