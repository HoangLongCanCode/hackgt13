package com.hackgt13.phase1collector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var destinationInput: EditText
    private lateinit var statusText: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        statusText.text = if (granted) {
            "Permissions granted. Ready to capture."
        } else {
            "Location permission denied."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        destinationInput = findViewById(R.id.destinationInput)
        statusText = findViewById(R.id.statusText)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val checkButton = findViewById<Button>(R.id.checkButton)

        startButton.setOnClickListener {
            if (hasRequiredPermissions()) {
                startCapture()
            } else {
                requestPermissions()
            }
        }

        stopButton.setOnClickListener {
            stopCapture()
        }

        checkButton.setOnClickListener {
            statusText.text = if (hasRequiredPermissions()) {
                "Permissions already granted."
            } else {
                "Permissions still needed."
            }
        }
    }

    private fun startCapture() {
        if (!isLocationEnabled()) {
            statusText.text = "Phone location is off. Turn on Location in system settings before starting capture."
            return
        }

        val destination = destinationInput.text?.toString()?.trim().orEmpty()
        val intent = Intent(this, LocationCaptureService::class.java).apply {
            action = LocationCaptureService.ACTION_START
            putExtra(LocationCaptureService.EXTRA_DESTINATION, destination)
        }
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "Capture started. Session is writing JSONL locally."
    }

    private fun stopCapture() {
        val intent = Intent(this, LocationCaptureService::class.java).apply {
            action = LocationCaptureService.ACTION_STOP
        }
        startService(intent)
        statusText.text = "Capture stopped."
    }

    private fun hasRequiredPermissions(): Boolean {
        val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        val notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return locationGranted && notificationGranted
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            try {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }
}
