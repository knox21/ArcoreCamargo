package com.arcoregeo.campoar.ui.ar

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.rotation
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * AR places the IFC mesh with a yaw around the up axis. SceneView turns that Euler
 * angle into a quaternion, so the sign convention has to be the one the placement
 * math assumes — otherwise the model comes out mirrored on rotated sites.
 */
class MeshPlacementTest {

    @Test
    fun `yaw rotates model axes onto east and north as the footprint does`() {
        val degrees = 30f
        val theta = Math.toRadians(degrees.toDouble())
        val matrix = rotation(Quaternion.fromEuler(Float3(0f, degrees, 0f)))

        // A vertex one metre along the model X axis (mesh x, up, -northish z).
        val alongX = matrix * Float4(1f, 0f, 0f, 1f)
        assertEquals(cos(theta), alongX.x.toDouble(), 1e-5)
        assertEquals(-sin(theta), alongX.z.toDouble(), 1e-5)

        // Scene z is negative north, so the same vertex must sit at bearing θ from east.
        val east = alongX.x.toDouble()
        val north = -alongX.z.toDouble()
        assertEquals(cos(theta), east, 1e-5)
        assertEquals(sin(theta), north, 1e-5)
    }
}
