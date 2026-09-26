package com.drivingassist.spatialcopilot.nav

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A WGS84 point in degrees. */
data class LatLng(val lat: Double, val lng: Double)

/** A point on the map card: metres right / up of the car (heading-up). */
data class MapPoint(val right: Double, val up: Double)

/**
 * The route drawn on the tablet's mini-map, from phase1's `navigation.packet`: the encoded route polyline (Google's
 * overview polyline, or the mock provider's) and the car's matched position. No map tiles and no Maps SDK key: it is
 * the route line only. Pure Kotlin, unit-tested.
 */
object RouteMap {
    /** Google encoded-polyline algorithm (precision 1e5). Invalid input stops at the last complete point. */
    fun decode(encoded: String): List<LatLng> {
        val out = ArrayList<LatLng>()
        var index = 0
        var lat = 0
        var lng = 0
        fun next(): Int? {
            var result = 0
            var shift = 0
            while (true) {
                if (index >= encoded.length) return null
                val b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
                if (b < 0x20) break
            }
            return if (result and 1 != 0) (result shr 1).inv() else result shr 1
        }
        while (index < encoded.length) {
            val dLat = next() ?: break
            val dLng = next() ?: break
            lat += dLat
            lng += dLng
            out += LatLng(lat / 1e5, lng / 1e5)
        }
        return out
    }

    /**
     * [points] relative to the car at [car], rotated so the car's [headingDegrees] (clockwise from north) points up.
     * Local flat projection: plenty for the few hundred metres the card shows.
     */
    fun project(points: List<LatLng>, car: LatLng, headingDegrees: Double): List<MapPoint> {
        val mPerDegLat = 110_540.0
        val mPerDegLng = 111_320.0 * cos(car.lat * PI / 180.0)
        val h = headingDegrees * PI / 180.0
        val c = cos(h)
        val s = sin(h)
        return points.map { p ->
            val east = (p.lng - car.lng) * mPerDegLng
            val north = (p.lat - car.lat) * mPerDegLat
            // Rotate the world by -heading so the heading direction becomes "up".
            MapPoint(right = east * c - north * s, up = east * s + north * c)
        }
    }

    /** Heading along the route at the point nearest the car (degrees from north), for fixes without a bearing. */
    fun routeHeadingNear(points: List<LatLng>, car: LatLng): Double? {
        if (points.size < 2) return null
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in 0 until points.lastIndex) {
            val d = hypot(points[i].lat - car.lat, (points[i].lng - car.lng) * cos(car.lat * PI / 180.0))
            if (d < bestD) { bestD = d; best = i }
        }
        val a = points[best]
        val b = points[best + 1]
        val east = (b.lng - a.lng) * cos(a.lat * PI / 180.0)
        val north = b.lat - a.lat
        if (east == 0.0 && north == 0.0) return null
        return (atan2(east, north) * 180.0 / PI + 360.0) % 360.0
    }
}
