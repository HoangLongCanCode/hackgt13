package com.hackgt13.phase1collector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.hackgt13.phase1collector.START"
        const val ACTION_STOP = "com.hackgt13.phase1collector.STOP"
        const val EXTRA_DESTINATION = "destination"
        const val CHANNEL_ID = "phase1_capture"
        const val NOTIFICATION_ID = 1001
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sessionStore: SessionStore
    private var writer: TripStateWriter? = null
    private var sessionPaths: SessionPaths? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val sample = location.toTripStateSample()
            writer?.append(sample)
            updateNotification(sample)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sessionStore = SessionStore(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startCapture(intent)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture(intent: Intent?) {
        if (sessionPaths == null) {
            val destination = intent?.getStringExtra(EXTRA_DESTINATION)
            sessionPaths = sessionStore.createSession(destination)
            writer = TripStateWriter(sessionPaths!!.tripStateFile)
        }

        startForeground(NOTIFICATION_ID, buildNotification("Capturing live GPS"))
        requestLocationUpdates()
        writeImmediateBestEffortSample()

    }

    private fun stopCapture() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        writer = null
        sessionPaths = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun requestLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(0f)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    private fun updateNotification(sample: TripStateSample) {
        val notification = buildNotification(
            "Lat ${"%.5f".format(sample.location.lat)} Lng ${"%.5f".format(sample.location.lng)}"
        )
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun writeImmediateBestEffortSample() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                val sample = location.toTripStateSample()
                writer?.append(sample)
                updateNotification(sample)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Phase 1 Collector")
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phase 1 capture",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun Location.toTripStateSample(): TripStateSample {
        val headingValue = if (hasBearing()) bearing.toDouble() else 0.0
        val speedValue = if (hasSpeed()) speed.toDouble() else 0.0
        val accuracyValue = if (hasAccuracy()) accuracy.toDouble() else null

        return TripStateSample(
            timestampMs = time,
            location = GeoPoint(latitude, longitude),
            heading = headingValue,
            speedMps = speedValue,
            accuracyMeters = accuracyValue,
        )
    }
}
