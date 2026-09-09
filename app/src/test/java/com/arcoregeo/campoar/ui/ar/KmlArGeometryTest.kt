package com.arcoregeo.campoar.ui.ar

import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.geo.GeoMath
import com.arcoregeo.campoar.geo.ReferenceCalibration
import dev.romainguy.kotlin.math.Float3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * KML overlays in AR are a filled surface, a line ribbon and location bubbles —
 * not the extruded posts that used to look like sticks.
 */
class KmlArGeometryTest {

    private val origin = LatLngAlt(-16.4205, -71.5284)
    private val calib = ReferenceCalibration(originGeo = origin, yawDegrees = 0.0)

    @Test
    fun `a polyline ribbon is a surface of triangles, not a stick`() {
        val path = listOf(
            Float3(0f, 0f, 0f),
            Float3(10f, 0f, 0f),
            Float3(10f, 0f, -8f),
        )
        val ribbon = polylineRibbonMesh(path, halfWidth = 0.12f)
        assertNotNull(ribbon)
        assertTrue(ribbon!!.indices.size >= 12)
        assertTrue(ribbon.vertices.size >= 8)
        val heights = ribbon.vertices.map { it.y }
        assertTrue(heights.max() - heights.min() < 0.5f)
    }

    @Test
    fun `manual yaw turns an east vertex around the GPS origin`() {
        val east = GeoMath.destination(origin, bearingDegrees = 90.0, distanceMeters = 20.0)
        val at0 = enuPosition(east, calib, heightOffsetMeters = 0f, yawOffsetDeg = 0f)
        val at90 = enuPosition(east, calib, heightOffsetMeters = 1.5f, yawOffsetDeg = 90f)

        assertEquals(20.0, at0.x.toDouble(), 0.3)
        assertEquals(0.0, at0.z.toDouble(), 0.3)
        assertEquals(0.0, at90.x.toDouble(), 0.3)
        assertEquals(-20.0, at90.z.toDouble(), 0.3)
        assertEquals(1.5f, at90.y, 1e-3f)
        assertEquals(
            sqrt(at0.x * at0.x + at0.z * at0.z).toDouble(),
            sqrt(at90.x * at90.x + at90.z * at90.z).toDouble(),
            0.05,
        )
    }

    @Test
    fun `bubble size stays readable on a small plot and a large one`() {
        assertEquals(0.22f, bubbleRadiusM(1f), 1e-4f)
        assertTrue(bubbleRadiusM(20f) in 0.22f..0.7f)
        assertEquals(0.7f, bubbleRadiusM(400f), 1e-4f)
    }

    @Test
    fun `a closed ring outline visits every corner`() {
        val ring = listOf(
            Float3(0f, 0f, 0f),
            Float3(8f, 0f, 0f),
            Float3(8f, 0f, -6f),
            Float3(0f, 0f, -6f),
        )
        val closed = ring + ring.first()
        val ribbon = polylineRibbonMesh(closed, 0.1f)
        assertNotNull(ribbon)
        // Four sides, two quads per segment, two triangles per quad (and the back faces).
        assertTrue(ribbon!!.indices.size >= 4 * 2 * 6)
        assertTrue(ribbon.vertices.all { abs(it.y) < 0.3f })
    }
}
