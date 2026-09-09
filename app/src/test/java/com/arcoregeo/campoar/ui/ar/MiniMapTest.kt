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
    fun `north stays up on the canvas regardless of heading`() {
        val at = miniMapCanvas(east = 0.0, north = 10.0, centerX = 50f, centerY = 50f, pixelsPerMetre = 1f)
        assertEquals(50f, at.x, 1e-4f)
        assertEquals(40f, at.y, 1e-4f)
    }

    @Test
    fun `east is to the right of the standing GPS fix`() {
        val at = miniMapCanvas(east = 10.0, north = 0.0, centerX = 50f, centerY = 50f, pixelsPerMetre = 1f)
        assertEquals(60f, at.x, 1e-4f)
        assertEquals(50f, at.y, 1e-4f)
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
        val sketch = miniMapSketch(document, you, headingDeg = 90f)
        assertEquals(1, sketch.points.size)
        assertTrue(sketch.points[0].east > 30.0)
        assertEquals(0.0, sketch.points[0].north, 1.0)
        assertEquals(1, sketch.lines.size)
        assertEquals(1, sketch.rings.size)
        assertEquals(90f, sketch.headingDeg)
    }
}
