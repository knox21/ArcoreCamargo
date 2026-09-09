package com.arcoregeo.campoar.ui.ar

import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.data.LocalMesh
import com.arcoregeo.campoar.data.Vec3f
import com.arcoregeo.campoar.geo.GeoMath
import com.arcoregeo.campoar.geo.ReferenceCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** Revit puts the IFC origin on the project base point, not on the building. */
    @Test
    fun `mesh document is measured from the model centre, not from its origin`() {
        val origin = LatLngAlt(-16.42051897, -71.52842835)
        val document = KmzDocument(
            id = "d",
            fileName = "edificio.ifc",
            storedFileName = "",
            importedAtEpochMs = 0L,
            points = emptyList(),
            sourceKind = "ifc",
            // 20 m box sitting 100 m east and 40 m north of the IFC origin.
            localMeshes = listOf(
                LocalMesh(
                    name = "caja",
                    vertices = listOf(
                        Vec3f(90f, 0f, -30f),
                        Vec3f(110f, 0f, -30f),
                        Vec3f(110f, 6f, -50f),
                        Vec3f(90f, 6f, -50f),
                    ),
                    indices = listOf(0, 1, 2, 0, 2, 3),
                ),
            ),
            meshOrigin = origin,
        )

        val extent = solidExtentOf(document, null)
        val centre = extent.centroid!!
        val enu = GeoMath.toEnu(origin, centre)

        assertEquals(100.0, enu.east, 0.5)
        assertEquals(40.0, enu.north, 0.5)
        assertEquals(14.1, extent.halfExtentM.toDouble(), 0.5)
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

    @Test
    fun `the plan only pulls in a model past the trigger`() {
        val near = GeoMath.destination(centroid, 20.0, 60.0)
        val far = GeoMath.destination(centroid, 20.0, 300.0)

        val nearPlan = farViewPlan(centroid, near, halfExtentM = 15f, wasFarAway = false)
        assertFalse(nearPlan.farAway)
        assertEquals(60.0, nearPlan.realDistanceM!!, 0.5)

        val farPlan = farViewPlan(centroid, far, halfExtentM = 15f, wasFarAway = false)
        assertTrue(farPlan.farAway)
        assertEquals(300.0, farPlan.realDistanceM!!, 0.5)
        assertEquals(
            viewDistanceFor(15f).toDouble(),
            GeoMath.distanceMeters(farPlan.standoff!!.originGeo, centroid),
            0.5,
        )
    }

    /** Between the two thresholds the answer depends on where it was, not on noise. */
    @Test
    fun `leaving the far view needs a shorter distance than entering it`() {
        val between = GeoMath.destination(centroid, 20.0, 90.0)

        assertFalse(farViewPlan(centroid, between, 15f, wasFarAway = false).farAway)
        assertTrue(farViewPlan(centroid, between, 15f, wasFarAway = true).farAway)
    }

    @Test
    fun `a plan without a fix places nothing`() {
        assertFalse(farViewPlan(centroid, null, 15f, wasFarAway = false).farAway)
        assertNull(farViewPlan(null, centroid, 15f, wasFarAway = false).realDistanceM)
    }

    @Test
    fun `the reported distance is rounded so the hud does not flicker`() {
        val viewer = GeoMath.destination(centroid, 20.0, 302.0)
        val plan = farViewPlan(centroid, viewer, 15f, wasFarAway = false)

        assertEquals(300.0, plan.roundedDistanceM!!, 0.001)
    }

    /**
     * A node under an anchor is drawn at the anchor's own world position plus its ENU
     * offset from the calibration origin, so the two have to be picked as a pair. This
     * is where the solid ended up on top of the viewer: an origin that says "you" while
     * the anchor says something else puts the model wherever the mismatch lands.
     */
    private fun drawnOffsetFromViewer(
        anchorGeo: LatLngAlt,
        originGeo: LatLngAlt,
        point: LatLngAlt,
        viewer: LatLngAlt,
    ): Pair<Double, Double> {
        val anchorAt = GeoMath.toEnu(viewer, anchorGeo)
        val offset = ReferenceCalibration(originGeo = originGeo, yawDegrees = 0.0).enuOf(point)
        return (anchorAt.east + offset.east) to (anchorAt.north + offset.north)
    }

    @Test
    fun `a near model is drawn at its true offset in both placement modes`() {
        val viewer = GeoMath.destination(centroid, 20.0, 60.0)
        val truth = GeoMath.toEnu(viewer, centroid)

        // GPS anchors at your feet and measures from your fix.
        val gps = drawnOffsetFromViewer(viewer, viewer, centroid, viewer)
        assertEquals(truth.east, gps.first, 0.2)
        assertEquals(truth.north, gps.second, 0.2)

        // Geospatial anchors the model's own place, which is absolute.
        val geospatial = drawnOffsetFromViewer(centroid, centroid, centroid, viewer)
        assertEquals(truth.east, geospatial.first, 0.2)
        assertEquals(truth.north, geospatial.second, 0.2)
    }

    @Test
    fun `a far model lands at the standoff in both placement modes`() {
        val viewer = GeoMath.destination(centroid, 20.0, 300.0)
        val plan = farViewPlan(centroid, viewer, halfExtentM = 15f, wasFarAway = false)
        val standoff = plan.standoff!!
        val realBearing = GeoMath.bearingDegrees(viewer, centroid)

        // Both modes anchor next to you and measure the model from the standoff origin.
        listOf(
            "gps" to drawnOffsetFromViewer(viewer, standoff.originGeo, centroid, viewer),
            "geospatial" to
                drawnOffsetFromViewer(standoff.viewerGeo, standoff.originGeo, centroid, viewer),
        ).forEach { (mode, drawn) ->
            val (east, north) = drawn
            assertEquals(
                "$mode drew it at the wrong distance",
                viewDistanceFor(15f).toDouble(),
                sqrt(east * east + north * north),
                0.5,
            )
            assertEquals(
                "$mode drew it on the wrong bearing",
                realBearing,
                (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0,
                0.5,
            )
        }
    }

    /** Anchoring the standoff origin instead would leave the model 300 m away. */
    @Test
    fun `anchoring the standoff origin would not bring the model closer`() {
        val viewer = GeoMath.destination(centroid, 20.0, 300.0)
        val standoff = farViewPlan(centroid, viewer, 15f, wasFarAway = false).standoff!!

        val (east, north) =
            drawnOffsetFromViewer(standoff.originGeo, standoff.originGeo, centroid, viewer)

        assertEquals(300.0, sqrt(east * east + north * north), 1.0)
    }

    @Test
    fun `acercar at more than a kilometre uses the same standoff as Traer aqui`() {
        val viewer = GeoMath.destination(centroid, 20.0, 1_250.0)
        val plan = farViewPlan(
            centroid, viewer, halfExtentM = 15f, wasFarAway = false, forceCloseUp = true,
        )

        assertTrue(plan.farAway)
        assertEquals(1_250.0, plan.realDistanceM!!, 1.0)
        assertEquals(
            viewDistanceFor(15f).toDouble(),
            GeoMath.distanceMeters(plan.standoff!!.originGeo, centroid),
            0.5,
        )
        val drawn = drawnOffset(viewer, viewDistanceFor(15f).toDouble())
        val bearing = (Math.toDegrees(atan2(drawn.first, drawn.second)) + 360.0) % 360.0
        assertEquals(GeoMath.bearingDegrees(viewer, centroid), bearing, 0.5)
    }

    @Test
    fun `acercar pulls in a model that is still under the automatic 100 m trigger`() {
        val viewer = GeoMath.destination(centroid, 20.0, 50.0)
        assertFalse(farViewPlan(centroid, viewer, 15f, wasFarAway = false).farAway)
        assertTrue(
            farViewPlan(centroid, viewer, 15f, wasFarAway = false, forceCloseUp = true).farAway,
        )
    }

    @Test
    fun `a kilometre away turns Acercar on by default`() {
        assertFalse(shouldAcercarByDefault(400.0))
        assertTrue(shouldAcercarByDefault(1_200.0))
    }

    @Test
    fun `session yaw is zero when the camera already looks the compass heading`() {
        // Camera looking along -Z, so its +Z axis is +Z.
        assertEquals(0.0, sessionYawDegrees(0.0, cameraZAxisX = 0.0, cameraZAxisZ = 1.0), 1e-6)
        // Camera looking +X (east in a north-aligned session): +Z_cam maps to -X.
        assertEquals(0.0, sessionYawDegrees(90.0, cameraZAxisX = -1.0, cameraZAxisZ = 0.0), 1e-6)
    }
}
