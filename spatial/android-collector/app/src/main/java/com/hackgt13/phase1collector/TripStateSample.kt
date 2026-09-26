package com.hackgt13.phase1collector

data class TripStateSample(
    val timestampMs: Long,
    val location: GeoPoint,
    val heading: Double,
    val speedMps: Double,
    val accuracyMeters: Double? = null,
)

data class GeoPoint(
    val lat: Double,
    val lng: Double,
)
