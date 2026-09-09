package com.arcoregeo.campoar.geo

import com.arcoregeo.campoar.data.LatLngAlt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Turns the first noisy GPS/compass readings into one place and one heading.
 *
 * A fresh fix can sit tens of metres from the next, and the compass swings several
 * degrees before it settles. Pinning the model to each of those would make it jump.
 * Samples are kept for a few seconds and averaged, weighted by how tight the fix
 * claimed to be; a lone outlier is ignored until a second one agrees.
 */
class PoseSmoother(
    private val windowMs: Long = 4_000L,
    private val minSamples: Int = 4,
    private val settleMs: Long = 1_200L,
    private val clusterMeters: Double = 18.0,
) {
    private val samples = ArrayDeque<Fix>()
    private var firstAtMs = 0L
    private var rejected: Fix? = null
    private var headingSin = 0.0
    private var headingCos = 0.0
    private var headingReady = false

    val settled: Boolean
        get() = samples.size >= minSamples ||
            (samples.isNotEmpty() && samples.last().atMs - firstAtMs >= settleMs)

    fun observe(pose: DevicePose, nowMs: Long): DevicePose {
        rememberHeading(pose)
        rememberFix(pose, nowMs)
        val coordinate = averageCoordinate() ?: pose.coordinate
        return DevicePose(
            coordinate = coordinate,
            accuracyMeters = pose.accuracyMeters,
            headingDegrees = smoothedHeading(pose),
            hasHeading = pose.hasHeading,
            stable = settled,
        )
    }

    private fun rememberFix(pose: DevicePose, nowMs: Long) {
        if (samples.isEmpty()) firstAtMs = nowMs
        val fix = Fix(pose.coordinate, pose.accuracyMeters.coerceAtLeast(1f).toDouble(), nowMs)
        while (samples.isNotEmpty() && nowMs - samples.first().atMs > windowMs) {
            samples.removeFirst()
        }
        val mean = averageCoordinate()
        if (mean != null && samples.size >= 2) {
            val jump = GeoMath.distanceMeters(mean, fix.coordinate)
            if (jump > clusterMeters) {
                val previous = rejected
                val samePlace = previous != null &&
                    GeoMath.distanceMeters(previous.coordinate, fix.coordinate) <= clusterMeters
                rejected = fix
                if (samePlace) {
                    samples.clear()
                    samples.addLast(fix)
                    firstAtMs = nowMs
                    rejected = null
                }
                return
            }
        }
        rejected = null
        samples.addLast(fix)
    }

    private fun averageCoordinate(): LatLngAlt? {
        if (samples.isEmpty()) return null
        val best = samples.minBy { it.accuracyM }
        val cluster = samples.filter {
            GeoMath.distanceMeters(best.coordinate, it.coordinate) <=
                max(clusterMeters, best.accuracyM * 2.0)
        }
        var wLat = 0.0
        var wLon = 0.0
        var wAlt = 0.0
        var wAltSum = 0.0
        var weight = 0.0
        cluster.forEach { fix ->
            val w = 1.0 / (fix.accuracyM * fix.accuracyM)
            wLat += fix.coordinate.latitude * w
            wLon += fix.coordinate.longitude * w
            val alt = fix.coordinate.altitude
            if (alt != null) {
                wAlt += alt * w
                wAltSum += w
            }
            weight += w
        }
        if (weight <= 0.0) return best.coordinate
        return LatLngAlt(
            latitude = wLat / weight,
            longitude = wLon / weight,
            altitude = if (wAltSum > 0.0) wAlt / wAltSum else best.coordinate.altitude,
        )
    }

    private fun rememberHeading(pose: DevicePose) {
        if (!pose.hasHeading) return
        val rad = Math.toRadians(pose.headingDegrees.toDouble())
        val s = sin(rad)
        val c = cos(rad)
        if (!headingReady) {
            headingSin = s
            headingCos = c
            headingReady = true
        } else {
            headingSin = headingSin * (1.0 - HEADING_ALPHA) + s * HEADING_ALPHA
            headingCos = headingCos * (1.0 - HEADING_ALPHA) + c * HEADING_ALPHA
        }
    }

    private fun smoothedHeading(pose: DevicePose): Float {
        if (!pose.hasHeading || !headingReady) return pose.headingDegrees
        val degrees = Math.toDegrees(kotlin.math.atan2(headingSin, headingCos))
        return ((degrees + 360.0) % 360.0).toFloat()
    }

    private data class Fix(
        val coordinate: LatLngAlt,
        val accuracyM: Double,
        val atMs: Long,
    )

    private companion object {
        const val HEADING_ALPHA = 0.18
    }
}
