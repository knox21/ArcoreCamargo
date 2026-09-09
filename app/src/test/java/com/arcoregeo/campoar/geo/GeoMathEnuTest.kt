package com.arcoregeo.campoar.geo

import com.arcoregeo.campoar.data.LatLngAlt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The AR frame is built by subtracting two geographic points. The phone always
 * reports its own altitude while an IFC or a KML rarely carries one, so treating a
 * missing altitude as sea level buried the solid as deep as the site is high — 2.3 km
 * under Arequipa, well outside the camera frustum.
 */
class GeoMathEnuTest {

    private val arequipa = LatLngAlt(-16.42051897, -71.52842835, altitude = 2_335.0)

    @Test
    fun `a model without altitude stands on the origin plane`() {
        val model = LatLngAlt(-16.42051897, -71.52842835)

        assertEquals(0.0, GeoMath.toEnu(arequipa, model).up, 1e-9)
        assertEquals(0.0, GeoMath.toEnu(model, arequipa).up, 1e-9)
    }

    @Test
    fun `two known altitudes keep their real difference`() {
        val roof = LatLngAlt(arequipa.latitude, arequipa.longitude, altitude = 2_347.0)

        assertEquals(12.0, GeoMath.toEnu(arequipa, roof).up, 1e-9)
        assertEquals(-12.0, GeoMath.toEnu(roof, arequipa).up, 1e-9)
    }

    @Test
    fun `the horizontal offset is unaffected`() {
        val target = GeoMath.destination(arequipa, bearingDegrees = 90.0, distanceMeters = 50.0)
        val enu = GeoMath.toEnu(arequipa, LatLngAlt(target.latitude, target.longitude))

        assertEquals(50.0, enu.east, 0.2)
        assertEquals(0.0, enu.north, 0.2)
    }

    @Test
    fun `a 90 degree yaw turns east into north`() {
        val (east, north) = GeoMath.rotateYaw(10.0, 0.0, 90.0)
        assertEquals(0.0, east, 1e-9)
        assertEquals(10.0, north, 1e-9)
    }

    @Test
    fun `yaw wrap stays in the open interval around zero`() {
        assertEquals(-175f, GeoMath.wrapYawDegrees(185f), 0.01f)
        assertEquals(0f, GeoMath.wrapYawDegrees(0f), 0.01f)
        assertEquals(-5f, GeoMath.wrapYawDegrees(-5f), 0.01f)
    }
}
