package com.arcoregeo.campoar.ui.ifc

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * A complex IFC used to open on an empty screen: the camera stood 1.8 times the
 * model size away while the far plane stayed at SceneView's default 30 m, so the
 * whole building was clipped. The model has to fit between the planes at any size.
 */
class ViewerFramingTest {

    private fun radiusOf(framing: ViewerFraming): Float {
        val p = framing.cameraPosition
        return sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
    }

    @Test
    fun `model fits between the clipping planes at every size`() {
        listOf(1f, 3f, 12f, 40f, 120f, 480f, 2_500f).forEach { span ->
            val framing = framingFor(span)
            val radius = radiusOf(framing)

            assertTrue(
                "span $span m is behind the far plane (${framing.far} m)",
                framing.far > radius + span,
            )
            assertTrue(
                "span $span m crosses the near plane (${framing.near} m)",
                framing.near < radius - span,
            )
            assertTrue("camera inside the model at $span m", radius > span)
        }
    }

    @Test
    fun `degenerate sizes fall back to a usable camera`() {
        listOf(Float.NaN, 0f, -5f, Float.POSITIVE_INFINITY).forEach { span ->
            val framing = framingFor(span)
            val radius = radiusOf(framing)

            assertTrue("radius was $radius for $span", radius.isFinite() && radius > 0f)
            assertTrue("far was ${framing.far} for $span", framing.far > framing.near)
        }
    }
}
