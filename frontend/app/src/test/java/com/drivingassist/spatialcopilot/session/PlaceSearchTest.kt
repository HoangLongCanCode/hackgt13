package com.drivingassist.spatialcopilot.session

import com.drivingassist.copilot.perception.GeoPoint
import com.drivingassist.copilot.perception.NavigationPlacesMessage
import com.drivingassist.copilot.perception.PlaceResult
import com.drivingassist.spatialcopilot.ui.formatPlaceDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlaceSearchTest {
    private val cafe = PlaceResult("p1", "Foxtail Coffee", "811 Peachtree St NE", GeoPoint(33.7766, -84.3838), 1162.0)

    private fun answer(id: String, places: List<PlaceResult> = listOf(cafe), error: String? = null) =
        NavigationPlacesMessage(serverTimeMs = 1, requestId = id, query = "coffee", provider = "google", places = places, error = error)

    @Test
    fun `only the answer to this search is applied`() {
        val pending = PlaceSearchState(query = "coffee", requestId = "s2", pending = true)
        assertSame("an older search's answer", pending, pending.withAnswer(answer("s1")))
        assertSame(pending, pending.withAnswer(null))
        val done = pending.withAnswer(answer("s2"))
        assertFalse(done.pending)
        assertEquals(listOf(cafe), done.places)
        assertEquals("google", done.provider)
        assertNull(done.error)
        val failed = pending.withAnswer(answer("s2", emptyList(), "rate limited"))
        assertEquals("rate limited", failed.error)
        assertEquals(emptyList<PlaceResult>(), failed.places)
        val notSent = PlaceSearchState(query = "coffee", error = PlaceSearchState.NOT_CONNECTED)
        assertSame("nothing was sent: no answer applies", notSent, notSent.withAnswer(answer("s2")))
    }

    @Test
    fun `a search without an answer times out, an answered one does not`() {
        val pending = PlaceSearchState(query = "coffee", requestId = "s3", pending = true)
        assertEquals(PlaceSearchState.NO_ANSWER, pending.timedOut("s3").error)
        assertSame("a newer search is pending", pending, pending.timedOut("s2"))
        val done = pending.withAnswer(answer("s3"))
        assertSame(done, done.timedOut("s3"))
    }

    @Test
    fun `picked place location survives the preferences round trip`() {
        val p = GeoPoint(33.7766, -84.3838)
        assertEquals(p, AppSettings.parseLocation(AppSettings.formatLocation(p)))
        assertNull(AppSettings.parseLocation(null))
        assertNull(AppSettings.parseLocation("33.7"))
        assertNull(AppSettings.parseLocation("91.0,10.0"))
        assertNull(AppSettings.parseLocation("a,b"))
    }

    @Test
    fun `result distances read in m below 1 km, else km`() {
        assertEquals("0 m", formatPlaceDistance(0.0))
        assertEquals("850 m", formatPlaceDistance(848.0))
        assertEquals("1.0 km", formatPlaceDistance(996.0))
        assertEquals("1.2 km", formatPlaceDistance(1162.0))
        assertEquals("14 km", formatPlaceDistance(14_321.0))
    }
}
