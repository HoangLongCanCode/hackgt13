package com.drivingassist.spatialcopilot.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.drivingassist.copilot.bridge.PerceptionBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The newest location fix as the UI needs it (degraded-nav banner, optional speed gate, place search bias). */
data class GpsFix(
    /** SystemClock.elapsedRealtime() when the fix arrived. */
    val receivedAtMs: Long,
    /** Degrees (WGS84), `Location.latitude` / `longitude`. */
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Double?,
    val speedMps: Double?,
    val provider: String,
)

/**
 * LIVE navigation: device location -> `client.trip_state` (~1 Hz) for the phase1 relay on the laptop.
 * Plain `android.location.LocationManager`, no Play Services.
 *
 * Provider order: fused (API 31+), GPS, network. The passive provider is never used: it only delivers
 * fixes another app requested. The Galaxy Tab S9 Wi-Fi model has no GPS receiver, so without fused it
 * ends up on Wi-Fi positioning (tens of metres), which the UI reports as poor accuracy.
 * Never throws: no provider / no permission -> [start] returns false.
 */
class LocationFeeder(private val context: Context, private val bridge: PerceptionBridge) {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var lastSentMs = 0L

    private val _fix = MutableStateFlow<GpsFix?>(null)

    /** Newest fix (sent or not), null before the first one. */
    val fix: StateFlow<GpsFix?> = _fix.asStateFlow()

    @Volatile var provider: String? = null
        private set

    /** Main thread. True when updates were requested. */
    fun start(): Boolean {
        val lm = manager ?: run { Log.w(TAG, "no LocationManager on this device"); return false }
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) { Log.w(TAG, "location permission not granted"); return false }
        val enabled = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val fused = if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null
        val chosen = listOfNotNull(
            fused,
            LocationManager.GPS_PROVIDER.takeIf { fine },
            LocationManager.NETWORK_PROVIDER,
        ).firstOrNull { it in enabled }
        if (chosen == null) {
            Log.w(TAG, "no enabled location provider (enabled=$enabled)")
            return false
        }
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = send(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                // High accuracy at 1 Hz; the default request only reports on change, so a still tablet goes quiet.
                val request = LocationRequest.Builder(1_000L)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(500L)
                    .build()
                lm.requestLocationUpdates(chosen, request, context.mainExecutor, l)
            } else {
                lm.requestLocationUpdates(chosen, 1_000L, 0f, l, Looper.getMainLooper())
            }
            listener = l
            provider = chosen
            Log.i(TAG, "location updates from '$chosen' -> client.trip_state")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission revoked", e); false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "provider '$chosen' unavailable", e); false
        }
    }

    fun stop() {
        listener?.let { l -> runCatching { manager?.removeUpdates(l) } }
        listener = null
    }

    private fun send(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        _fix.value = GpsFix(
            receivedAtMs = now,
            lat = loc.latitude,
            lng = loc.longitude,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            provider = loc.provider ?: provider ?: "?",
        )
        if (now - lastSentMs < 800) return // ~1 Hz
        lastSentMs = now
        bridge.sendTripState(
            timestampMs = loc.time,
            lat = loc.latitude,
            lng = loc.longitude,
            headingDegrees = if (loc.hasBearing()) loc.bearing.toDouble() else null,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "LocationFeeder"
    }
}
