package com.hackgt13.phase1collector

import org.json.JSONObject

fun TripStateSample.toJsonObject(): JSONObject {
    val locationJson = JSONObject()
        .put("lat", location.lat)
        .put("lng", location.lng)

    val json = JSONObject()
        .put("timestampMs", timestampMs)
        .put("location", locationJson)
        .put("heading", heading)
        .put("speedMps", speedMps)

    if (accuracyMeters != null) {
        json.put("accuracyMeters", accuracyMeters)
    }

    return json
}
