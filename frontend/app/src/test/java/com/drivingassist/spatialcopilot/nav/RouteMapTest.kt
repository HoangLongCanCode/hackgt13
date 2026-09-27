package com.drivingassist.spatialcopilot.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMapTest {
    @Test
    fun `decodes Google's reference polyline`() {
        val p = RouteMap.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, p.size)
        assertEquals(38.5, p[0].lat, 1e-9)
        assertEquals(-120.2, p[0].lng, 1e-9)
        assertEquals(43.252, p[2].lat, 1e-9)
        assertEquals(-126.453, p[2].lng, 1e-9)
        assertTrue("truncated input stops cleanly", RouteMap.decode("_p~iF~ps").size <= 1)
    }

    @Test
    fun `heading-up projection puts the road ahead above the car`() {
        val car = LatLng(33.7756, -84.3963)
        val north = LatLng(33.7756 + 100 / 110_540.0, -84.3963) // 100 m north
        val up = RouteMap.project(listOf(north), car, headingDegrees = 0.0).single()
        assertEquals(0.0, up.right, 0.5)
        assertEquals(100.0, up.up, 0.5)
        // Driving east: the same point is now 100 m to the left.
        val east = RouteMap.project(listOf(north), car, headingDegrees = 90.0).single()
        assertEquals(-100.0, east.right, 0.5)
        assertEquals(0.0, east.up, 0.5)
        assertEquals(90.0, RouteMap.routeHeadingNear(listOf(car, LatLng(33.7756, -84.3950)), car)!!, 0.5)
    }
}
