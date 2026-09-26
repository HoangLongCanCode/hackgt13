package com.drivingassist.glass.perception

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.drivingassist.glass.MockDataViewModel
import com.drivingassist.glass.VisionSource

/**
 * Builds the vision source and (optional) route source for a [PerceptionConfig]:
 *
 * | source | vision                                  | route                                         |
 * |--------|-----------------------------------------|-----------------------------------------------|
 * | MOCK   | MockVisionSource (via `MockDataViewModel()`) | the ViewModel's 5 s mock loop               |
 * | LIVE   | [LaptopVisionSource] (camera uplink)    | [BridgeRouteSource] if navEnabled, else mock loop |
 * | SIM    | [SimVisionSource] (ExoPlayer)           | [BridgeRouteSource] if navEnabled, else mock loop |
 *
 * LIVE / SIM share one [PerceptionRuntime] (one WebSocket). Call on the main thread (ExoPlayer).
 */
object PerceptionFactory {
    private const val TAG = "PerceptionFactory"

    data class Sources(val vision: VisionSource?, val route: RouteSource?)

    fun create(context: Context, config: PerceptionConfig): Sources {
        Log.i(TAG, "source=${config.source} url=${config.serverUrl} nav=${config.navEnabled}")
        return when (config.source) {
            PerceptionSource.MOCK -> Sources(null, null)
            PerceptionSource.LIVE -> {
                val runtime = PerceptionRuntime(context, config)
                Sources(LaptopVisionSource(runtime), if (config.navEnabled) BridgeRouteSource(runtime) else null)
            }
            PerceptionSource.SIM -> {
                val runtime = PerceptionRuntime(context, config)
                Sources(SimVisionSource(runtime, context), if (config.navEnabled) BridgeRouteSource(runtime) else null)
            }
        }
    }

    /** MOCK builds exactly `MockDataViewModel()` (unchanged behaviour); LIVE / SIM pass the laptop sources. */
    fun createViewModel(context: Context, config: PerceptionConfig): MockDataViewModel {
        val sources = create(context, config)
        val vision = sources.vision ?: return MockDataViewModel()
        return MockDataViewModel(vision, sources.route)
    }

    fun viewModelFactory(context: Context, config: PerceptionConfig): ViewModelProvider.Factory {
        val app = context.applicationContext
        return viewModelFactory { initializer { createViewModel(app, config) } }
    }
}
