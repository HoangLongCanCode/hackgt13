package com.drivingassist.glass

import androidx.camera.core.ImageAnalysis
import androidx.lifecycle.ViewModel
import com.drivingassist.glass.perception.RouteSource
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Glass shell. Vision frames come from [visionSource]. The route loop is a stand-in
 * for the Node.js engine and is not part of the OpenCV merge.
 *
 * ENGINEER A SWAP: replace [MockVisionSource] with the OpenCV implementation.
 * If that class implements [ImageAnalysis.Analyzer], camera frames are delivered automatically.
 *
 * PERCEPTION BRIDGE (PERCEPTION_INTEGRATION.md): `perception.PerceptionFactory` passes the laptop
 * vision source and, with navigation on, a [RouteSource] (phase1 via the laptop). When [routeSource]
 * is null (MOCK, or navigation off) the 5 s route loop below runs unchanged.
 */
class MockDataViewModel @JvmOverloads constructor(
    val visionSource: VisionSource = MockVisionSource(),
    private val routeSource: RouteSource? = null,
) : ViewModel() {

    val visionData: StateFlow<VisionData> = visionSource.visionData
    val frameGeometry: FrameGeometry = visionSource.frameGeometry
    val frameAnalyzer: ImageAnalysis.Analyzer? = visionSource as? ImageAnalysis.Analyzer

    private val routeScript = listOf(
        RouteBeat("MERGE_LEFT", "Merging left", "LANE_ARROW"),
        RouteBeat("TURN_RIGHT", "Turn right at the next intersection", "LANE_ARROW"),
        RouteBeat("CONTINUE", "Continue straight", "LANE_ARROW"),
        RouteBeat("TURN_LEFT", "Turn left at the light", "LANE_ARROW"),
        RouteBeat("MERGE_RIGHT", "Merge right", "LANE_ARROW"),
    )

    private val startedNanos = System.nanoTime()
    private val _routeState = MutableStateFlow(routeScript.first().toState(0f))
    val routeState: StateFlow<RouteState> = _routeState.asStateFlow()

    init {
        visionSource.start()
        if (routeSource == null) {
            viewModelScope.launch {
                var index = 0
                while (isActive) {
                    _routeState.value = routeScript[index].toState(nowSeconds())
                    index = (index + 1) % routeScript.size
                    delay(5_000)
                }
            }
        } else {
            viewModelScope.launch { routeSource.routeState.collect { _routeState.value = it } }
        }
    }

    override fun onCleared() {
        visionSource.stop()
    }

    private fun nowSeconds(): Float = (System.nanoTime() - startedNanos) / 1_000_000_000f

    private data class RouteBeat(
        val action: String,
        val audio: String,
        val ui: String,
    ) {
        fun toState(time: Float) = RouteState(
            time = time,
            action = action,
            audio = audio,
            ui = ui,
        )
    }
}
