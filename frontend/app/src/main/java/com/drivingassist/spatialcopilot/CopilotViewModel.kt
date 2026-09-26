package com.drivingassist.spatialcopilot

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivingassist.spatialcopilot.model.SpatialInstruction
import com.drivingassist.spatialcopilot.perception.LinkState
import com.drivingassist.spatialcopilot.perception.PerceptionClient
import com.drivingassist.spatialcopilot.perception.ServerEvent
import com.drivingassist.spatialcopilot.perception.World
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class CopilotUi(
    val instruction: SpatialInstruction,
    val link: LinkState,
    val linkDetail: String,
    val serverUrl: String,
)

class CopilotViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val initialUrl = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    private val world = MutableStateFlow(World())
    private val elapsed = MutableStateFlow(0.0)
    private val link = MutableStateFlow(LinkState.CONNECTING)
    private val linkDetail = MutableStateFlow("")
    private val serverUrl = MutableStateFlow(initialUrl)

    private val client = PerceptionClient(
        scope = viewModelScope,
        onEvent = { event ->
            when (event) {
                is ServerEvent.Ignored, is ServerEvent.Skip, is ServerEvent.Failure -> Unit
                else -> world.value = world.value.apply(event)
            }
        },
        onStatus = { state, detail ->
            link.value = state
            linkDetail.value = detail
        },
    )

    val ui: StateFlow<CopilotUi> = combine(world, elapsed, link, linkDetail, serverUrl) { geometry, time, state, detail, url ->
        CopilotUi(
            instruction = geometry.instruction(time),
            link = state,
            linkDetail = detail,
            serverUrl = url,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CopilotUi(
            instruction = World().instruction(0.0),
            link = LinkState.CONNECTING,
            linkDetail = "",
            serverUrl = initialUrl,
        ),
    )

    init {
        val started = SystemClock.elapsedRealtime()
        viewModelScope.launch {
            while (isActive) {
                elapsed.value = (SystemClock.elapsedRealtime() - started) / 1000.0
                delay(80)
            }
        }
        client.connect(initialUrl)
    }

    fun canSendFrame(): Boolean = client.canUplink()

    fun onCameraFrame(
        jpeg: ByteArray,
        rotationDegrees: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        timestampNs: Long,
    ) {
        client.noteUprightSize(bufferWidth, bufferHeight, rotationDegrees)
        val captureTimeNs = if (timestampNs > 0L) timestampNs else SystemClock.elapsedRealtimeNanos()
        client.offerFrame(jpeg, rotationDegrees, captureTimeNs)
    }

    fun updateServerUrl(raw: String): Boolean {
        val url = raw.trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) return false
        serverUrl.value = url
        prefs.edit().putString(KEY_URL, url).apply()
        client.connect(url)
        return true
    }

    override fun onCleared() {
        client.close()
    }

    private companion object {
        const val PREFS = "spatial_copilot"
        const val KEY_URL = "server_url"
        const val DEFAULT_URL = "ws://127.0.0.1:8765/perception"
    }
}
