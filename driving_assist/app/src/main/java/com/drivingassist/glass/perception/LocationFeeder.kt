package com.drivingassist.glass.perception

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.ksr.copilot.bridge.PerceptionBridge

/**
 * LIVE + navigation: device location -> `client.trip_state` (~1 Hz) for the phase1 relay on the
 * laptop. Plain `android.location.LocationManager` (no Play Services).
 *
 * Provider order: GPS, then network, then fused (API 31+). The passive provider is never used: it
 * only delivers fixes another app requested, so with Location switched off nothing would arrive;
 * start() returns false instead and [PerceptionRuntime] retries every few seconds. The Galaxy Tab S9
 * Wi-Fi model has no GPS receiver, so it usually ends up on the network provider (Wi-Fi positioning,
 * tens of metres): logged, not an error. Never throws: no provider / no permission -> start() = false.
 * A fix without bearing / speed is sent with 0 (the bridge does that; the contract needs numbers).
 */
class LocationFeeder(private val context: Context, private val bridge: PerceptionBridge) {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var lastSentMs = 0L

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
            LocationManager.GPS_PROVIDER.takeIf { fine },
            LocationManager.NETWORK_PROVIDER,
            fused,
        ).firstOrNull { it in enabled }
        if (chosen == null) {
            Log.w(TAG, "no enabled location provider (enabled=$enabled, all=${runCatching { lm.allProviders }.getOrNull()})")
            return false
        }
        if (chosen != LocationManager.GPS_PROVIDER) Log.i(TAG, "no GPS provider available; using '$chosen' for live navigation")
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = send(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        return try {
            lm.requestLocationUpdates(chosen, 1_000L, 0f, l, Looper.getMainLooper())
            listener = l
            provider = chosen
            runCatching { lm.getLastKnownLocation(chosen) }.getOrNull()?.let(::send)
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
