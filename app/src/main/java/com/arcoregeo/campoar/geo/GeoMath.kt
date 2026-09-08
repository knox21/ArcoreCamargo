package com.arcoregeo.campoar.geo

import com.arcoregeo.campoar.data.LatLngAlt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Local east/north/up meters relative to an origin. */
data class Enu(
    val east: Double,
    val north: Double,
    val up: Double = 0.0,
)

/**
 * Calibration that maps KMZ geographic vertices onto real-world AR plane hits.
 * 1 point → translation; 2 points → translation + yaw in plan.
 */
data class ReferenceCalibration(
    val originGeo: LatLngAlt,
    val yawDegrees: Double = 0.0,
    val refCount: Int = 1,
) {
    fun enuOf(geo: LatLngAlt): Enu {
        val raw = GeoMath.toEnu(originGeo, geo)
        if (yawDegrees == 0.0) return raw
        val rad = Math.toRadians(yawDegrees)
        val c = cos(rad)
        val s = sin(rad)
        return Enu(
            east = raw.east * c - raw.north * s,
            north = raw.east * s + raw.north * c,
            up = raw.up,
        )
    }

    companion object {
        fun fromControls(
            geo1: LatLngAlt,
            geo2: LatLngAlt? = null,
            worldBearing1to2Degrees: Double? = null,
        ): ReferenceCalibration {
            if (geo2 == null || worldBearing1to2Degrees == null) {
                return ReferenceCalibration(originGeo = geo1, yawDegrees = 0.0, refCount = 1)
            }
            // enuOf() rotates by -yaw, so yaw must be map bearing minus measured world bearing.
            val mapBearing = GeoMath.bearingDegrees(geo1, geo2)
            val yaw = GeoMath.wrappedDeltaDegrees(mapBearing, worldBearing1to2Degrees).toDouble()
            return ReferenceCalibration(originGeo = geo1, yawDegrees = yaw, refCount = 2)
        }
    }
}

object GeoMath {
    private const val EARTH_RADIUS_M = 6_378_137.0

    fun distanceMeters(from: LatLngAlt, to: LatLngAlt): Double {
        val dLat = Math.toRadians(to.latitude - from.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(from.latitude)) *
            cos(Math.toRadians(to.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearingDegrees(from: LatLngAlt, to: LatLngAlt): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun wrappedDeltaDegrees(targetBearing: Double, heading: Double): Float {
        val delta = (targetBearing - heading + 540.0) % 360.0 - 180.0
        return delta.toFloat()
    }

    /** Point [distanceMeters] away from [origin] on [bearingDegrees]; inverse of [toEnu]. */
    fun destination(origin: LatLngAlt, bearingDegrees: Double, distanceMeters: Double): LatLngAlt {
        val rad = Math.toRadians(bearingDegrees)
        val north = cos(rad) * distanceMeters
        val east = sin(rad) * distanceMeters
        val cosLat = cos(Math.toRadians(origin.latitude)).let { if (abs(it) < 1e-9) 1e-9 else it }
        return LatLngAlt(
            latitude = origin.latitude + Math.toDegrees(north / EARTH_RADIUS_M),
            longitude = origin.longitude + Math.toDegrees(east / (EARTH_RADIUS_M * cosLat)),
            altitude = origin.altitude,
        )
    }

    /** East/North/Up meters of [point] relative to [origin]. */
    fun toEnu(origin: LatLngAlt, point: LatLngAlt): Enu {
        val latRad = Math.toRadians(origin.latitude)
        val dLat = Math.toRadians(point.latitude - origin.latitude)
        val dLon = Math.toRadians(point.longitude - origin.longitude)
        val north = dLat * EARTH_RADIUS_M
        val east = dLon * EARTH_RADIUS_M * cos(latRad)
        val up = (point.altitude ?: 0.0) - (origin.altitude ?: 0.0)
        return Enu(east = east, north = north, up = up)
    }

    fun formatDistance(meters: Double): String {
        return if (meters >= 1000) {
            String.format("%.2f km", meters / 1000.0)
        } else {
            String.format("%.0f m", meters)
        }
    }

    fun expandBounds(
        southWest: LatLngAlt,
        northEast: LatLngAlt,
        paddingKm: Double = 1.5,
    ): Pair<LatLngAlt, LatLngAlt> {
        val padDeg = paddingKm / 111.0
        return LatLngAlt(
            latitude = southWest.latitude - padDeg,
            longitude = southWest.longitude - padDeg,
        ) to LatLngAlt(
            latitude = northEast.latitude + padDeg,
            longitude = northEast.longitude + padDeg,
        )
    }
}
