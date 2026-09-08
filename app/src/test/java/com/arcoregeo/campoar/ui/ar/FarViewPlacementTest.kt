package com.arcoregeo.campoar.ui.ar

import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.geo.GeoMath
import com.arcoregeo.campoar.geo.ReferenceCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * A model 300 m away is invisible in AR, so it is drawn closer. It has to keep the
 * compass direction and the heading it really has, otherwise the view lies about
 * where the plot is.
 */
class FarViewPlacementTest {

    private val centroid = LatLngAlt(-16.42051897, -71.52842835)

    private fun drawnOffset(viewer: LatLngAlt, standoff: Double): Pair<Double, Double> {
        val origin = farViewOrigin(centroid, viewer, standoff)
        val enu = ReferenceCalibration(originGeo = origin, yawDegrees = 0.0).enuOf(centroid)
        return enu.east to enu.north
    }

    @Test
    fun `far model is drawn at the standoff distance`() {
        val viewer = GeoMath.destination(centroid, 200.0, 340.0)
        val (east, north) = drawnOffset(viewer, 30.0)

        assertEquals(30.0, sqrt(east * east + north * north), 0.2)
    }

    @Test
    fun `far model keeps the bearing it really has`() {
        listOf(0.0, 47.0, 135.0, 200.0, 310.0).forEach { bearingToViewer ->
            val viewer = GeoMath.destination(centroid, bearingToViewer, 480.0)
            val (east, north) = drawnOffset(viewer, 40.0)

            val drawnBearing = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
            val realBearing = GeoMath.bearingDegrees(viewer, centroid)
            assertEquals(realBearing, drawnBearing, 0.5)
        }
    }

    @Test
    fun `standoff grows with the size of the solid`() {
        assertEquals(25f, viewDistanceFor(5f))
        assertEquals(58f, viewDistanceFor(30f))
        assertEquals(70f, viewDistanceFor(200f))
        assertTrue(viewDistanceFor(80f) <= 70f)
    }

    @Test
    fun `a nearby model is left at its true offset`() {
        val viewer = GeoMath.destination(centroid, 90.0, 60.0)
        val enu = ReferenceCalibration(originGeo = viewer, yawDegrees = 0.0).enuOf(centroid)

        // Under the trigger the phone position is the origin, so nothing is compressed.
        assertTrue(60.0 < FAR_VIEW_TRIGGER_M)
        assertEquals(60.0, sqrt(enu.east * enu.east + enu.north * enu.north), 0.2)
        assertEquals(-60.0, enu.east, 0.2)
    }
}
