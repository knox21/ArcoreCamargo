package com.arcoregeo.campoar.ui.ar

import com.arcoregeo.campoar.data.GeoLine
import com.arcoregeo.campoar.data.GeoPoint
import com.arcoregeo.campoar.data.GeoPolygon
import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMapTest {

    private val you = LatLngAlt(-16.4205, -71.5284)

    @Test
    fun `heading up puts north above you when the phone faces north`() {
        val (x, y) = headingUp(east = 0.0, north = 10.0, headingDeg = 0f)
        assertEquals(0.0, x, 1e-9)
        assertEquals(10.0, y, 1e-9)
    }

    @Test
    fun `heading up puts east above you when the phone faces east`() {
        val (x, y) = headingUp(east = 10.0, north = 0.0, headingDeg = 90f)
        assertEquals(0.0, x, 1e-6)
        assertEquals(10.0, y, 1e-6)
    }

    @Test
    fun `the sketch keeps the plot east of the GPS fix`() {
        val east = GeoMath.destination(you, 90.0, 40.0)
        val document = KmzDocument(
            id = "d",
            fileName = "p.kml",
            storedFileName = "",
            importedAtEpochMs = 0L,
            points = listOf(GeoPoint("1", "mojon", coordinate = east)),
            lines = listOf(
                GeoLine("l", "linde", listOf(you, east)),
            ),
            polygons = listOf(
                GeoPolygon(
                    "p",
                    "predio",
                    listOf(
                        you,
                        GeoMath.destination(you, 90.0, 20.0),
                        GeoMath.destination(you, 45.0, 20.0),
                        you,
                    ),
                ),
            ),
        )
        val sketch = miniMapSketch(document, you, headingDeg = 0f)
        assertEquals(1, sketch.points.size)
        assertTrue(sketch.points[0].east > 30.0)
        assertEquals(0.0, sketch.points[0].north, 1.0)
        assertEquals(1, sketch.lines.size)
        assertEquals(1, sketch.rings.size)
    }
}
