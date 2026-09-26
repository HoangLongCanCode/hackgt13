package com.drivingassist.spatialcopilot

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivingassist.spatialcopilot.session.AppSettings
import com.drivingassist.spatialcopilot.session.CopilotSession
import com.drivingassist.spatialcopilot.session.StatusModel
import com.drivingassist.spatialcopilot.session.StatusUi
import com.drivingassist.spatialcopilot.voice.VoiceCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Holds the settings and the current [CopilotSession]. A change of source, server, clip or camera
 * set-up closes the session and builds a new one; debug and voice switches apply in place.
 * Created on the main thread, which the session (ExoPlayer, LocationManager) needs.
 */
class CopilotViewModel(app: Application) : AndroidViewModel(app) {
    private val _settings = MutableStateFlow(AppSettings.load(app))
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val voice = VoiceCoordinator(app, viewModelScope)

    private val _session = MutableStateFlow(newSession(_settings.value))
    val session: StateFlow<CopilotSession> = _session.asStateFlow()

    private val _status = MutableStateFlow(StatusUi.STARTING)

    /** Chip text, banners and debug numbers, refreshed at 4 Hz (the link stats change every 50 ms). */
    val status: StateFlow<StatusUi> = _status.asStateFlow()

    private var intentApplied = false

    init {
        viewModelScope.launch {
            while (isActive) {
                _status.value = StatusModel.compute(_session.value, _settings.value, voice.state.value, SystemClock.elapsedRealtime())
                delay(250)
            }
        }
    }

    /** Launch extras (`perception.source`, `perception.url`, ...), applied once per activity instance. */
    fun onLaunchIntent(intent: Intent?) {
        if (intentApplied) return
        intentApplied = true
        val next = AppSettings.withExtras(_settings.value, intent)
        if (next != _settings.value) apply(next)
    }

    fun apply(next: AppSettings) {
        val previous = _settings.value
        if (next == previous) return
        _settings.value = next
        next.save(getApplication())
        if (sessionKey(next) != sessionKey(previous)) {
            _session.value.close()
            _session.value = newSession(next)
        } else if (next.voice != previous.voice) {
            voice.attach(_session.value, next)
        }
    }

    fun toggleDebug() = apply(_settings.value.copy(debug = !_settings.value.debug))

    fun reclaim() = _session.value.reclaim()

    override fun onCleared() {
        voice.close()
        _session.value.close()
    }

    /** The voice layer subscribes to the session's events before it connects (the events flow has no replay). */
    private fun newSession(s: AppSettings) = CopilotSession(getApplication(), s).also {
        voice.attach(it, s)
        it.start()
    }

    private fun sessionKey(s: AppSettings) = listOf(s.mode, s.serverUrl, s.simVideoId, s.mountHeightMeters, s.gateCriticalBySpeed, s.cameraOnMonitor)
}
