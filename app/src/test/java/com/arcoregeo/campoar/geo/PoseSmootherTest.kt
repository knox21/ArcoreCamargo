package com.arcoregeo.campoar.geo

import com.arcoregeo.campoar.data.LatLngAlt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PoseSmootherTest {

    private fun pose(
        lat: Double,
        lon: Double,
        accuracy: Float = 8f,
        heading: Float = 40f,
    ) = DevicePose(
        coordinate = LatLngAlt(lat, lon, 2_300.0),
        accuracyMeters = accuracy,
        headingDegrees = heading,
        hasHeading = true,
        stable = false,
    )

    @Test
    fun `the first fixes are averaged instead of pinning to the worst one`() {
        val smoother = PoseSmoother(minSamples = 4, settleMs = 2_000L)
        val origin = pose(-16.3988, -71.5369, accuracy = 25f)
        smoother.observe(origin, 0)
        smoother.observe(pose(-16.3988, -71.5367, accuracy = 18f), 400)
        smoother.observe(pose(-16.3989, -71.5368, accuracy = 12f), 800)
        val settled = smoother.observe(pose(-16.39885, -71.53685, accuracy = 6f), 1_200)

        assertTrue(smoother.settled)
        assertTrue(settled.stable)
        val dOrigin = GeoMath.distanceMeters(origin.coordinate, settled.coordinate)
        val dBest = GeoMath.distanceMeters(
            LatLngAlt(-16.39885, -71.53685, 2_300.0),
            settled.coordinate,
        )
        assertTrue("average stayed $dOrigin m from the first 25 m fix", dOrigin > 5.0)
        assertTrue("average should sit near the tight fixes, was $dBest m", dBest < 8.0)
    }

    @Test
    fun `a single flying fix does not drag the average`() {
        val smoother = PoseSmoother(minSamples = 3, settleMs = 5_000L)
        val here = pose(-16.40, -71.54, accuracy = 5f)
        smoother.observe(here, 0)
        smoother.observe(here, 400)
        smoother.observe(here, 800)
        val afterSpike = smoother.observe(pose(-16.41, -71.55, accuracy = 40f), 1_000)

        assertTrue(
            GeoMath.distanceMeters(here.coordinate, afterSpike.coordinate) < 5.0,
        )
    }

    @Test
    fun `two fixes in a new place are treated as a real move`() {
        val smoother = PoseSmoother(minSamples = 2, settleMs = 5_000L, clusterMeters = 18.0)
        val here = pose(-16.40, -71.54, accuracy = 5f)
        smoother.observe(here, 0)
        smoother.observe(here, 400)
        val there = pose(-16.4005, -71.5405, accuracy = 5f)
        smoother.observe(there, 800)
        val moved = smoother.observe(there, 1_200)

        assertTrue(GeoMath.distanceMeters(there.coordinate, moved.coordinate) < 5.0)
    }

    @Test
    fun `heading is low-passed so a one-frame spike does not turn the model`() {
        val smoother = PoseSmoother()
        smoother.observe(pose(-16.40, -71.54, heading = 10f), 0)
        smoother.observe(pose(-16.40, -71.54, heading = 12f), 20)
        val spiked = smoother.observe(pose(-16.40, -71.54, heading = 80f), 40)

        assertTrue(abs(GeoMath.wrappedDeltaDegrees(spiked.headingDegrees.toDouble(), 12.0)) < 20f)
        assertFalse(abs(GeoMath.wrappedDeltaDegrees(spiked.headingDegrees.toDouble(), 80.0)) < 15f)
    }

    @Test
    fun `not enough samples yet means the pose is not stable`() {
        val smoother = PoseSmoother(minSamples = 4, settleMs = 2_000L)
        val first = smoother.observe(pose(-16.40, -71.54), 0)
        assertFalse(first.stable)
        assertFalse(smoother.settled)
    }
}
