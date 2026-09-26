package com.hackgt13.phase1collector

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SessionPaths(
    val sessionDir: File,
    val manifestFile: File,
    val tripStateFile: File,
)

class SessionStore(private val context: Context) {
    fun createSession(destinationQuery: String? = null): SessionPaths {
        val timestamp = System.currentTimeMillis()
        val sessionId = "session_${timestamp}"
        val root = File(context.getExternalFilesDir(null), "phase1")
        val sessionDir = File(root, sessionId)
        sessionDir.mkdirs()

        val manifestFile = File(sessionDir, "session_manifest.json")
        val tripStateFile = File(sessionDir, "trip_state.jsonl")

        val manifestJson = JSONObject()
            .put("sessionId", sessionId)
            .put("capturedAtMs", timestamp)
            .put("capturedAtIso", iso(timestamp))
            .put("videoFile", "")
            .put("routeFile", "route.json")
            .put("tripStateFile", "trip_state.jsonl")
            .put("source", "device")
            .put("notes", buildNotes(destinationQuery))

        manifestFile.writeText(manifestJson.toString(2))
        tripStateFile.writeText("")

        return SessionPaths(sessionDir, manifestFile, tripStateFile)
    }

    private fun iso(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(Date(epochMs))
    }

    private fun buildNotes(destinationQuery: String?): String {
        return if (destinationQuery.isNullOrBlank()) {
            "Android live location capture"
        } else {
            "Android live location capture. Destination: $destinationQuery"
        }
    }
}
