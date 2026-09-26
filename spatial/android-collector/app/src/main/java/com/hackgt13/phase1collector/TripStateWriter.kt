package com.hackgt13.phase1collector

import org.json.JSONObject
import java.io.File

class TripStateWriter(private val file: File) {
    @Synchronized
    fun append(sample: TripStateSample) {
        val line = sample.toJsonObject().toString()
        file.appendText(line + "\n")
    }

    fun previewJson(sample: TripStateSample): String {
        return JSONObject(sample.toJsonObject().toString()).toString(2)
    }
}
