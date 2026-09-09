package com.arcoregeo.campoar.ui.ar

import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.data.centroidOf
import com.arcoregeo.campoar.data.openRing
import com.arcoregeo.campoar.geo.GeoMath
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Past this distance a georeferenced model is a couple of pixels tall and GPS noise
 * is larger than the model itself, so it is drawn closer than it really is.
 */
internal const val FAR_VIEW_TRIGGER_M = 100.0

/** Past this, Acercar is on by default so a kilometre-away plot is not a speck. */
internal const val ACERCAR_DEFAULT_M = 1_000.0

/** Leaving needs a shorter distance than entering, so GPS noise cannot flap the view. */
internal const val FAR_VIEW_EXIT_M = 80.0

/** Stand back enough to see the whole solid: 25 m for a plot, more for a large one. */
internal fun viewDistanceFor(halfExtentM: Float): Float =
    maxOf(25f, halfExtentM * 1.6f + 10f).coerceIn(25f, 70f)

/**
 * Viewer position to draw a far model from: [standoffM] away from [centroid] on the
 * line that joins it to [viewer]. Using it as the ENU origin pulls the model in
 * without turning it, so it still stands on its real bearing with its real heading.
 */
internal fun farViewOrigin(
    centroid: LatLngAlt,
    viewer: LatLngAlt,
    standoffM: Double,
): LatLngAlt = GeoMath.destination(
    origin = centroid,
    bearingDegrees = GeoMath.bearingDegrees(centroid, viewer),
    distanceMeters = standoffM,
)

/**
 * A drawn point stands for [originGeo] and sits where [viewerGeo] really is, so the
 * model lands [viewDistanceFor] metres away on its real bearing, keeping its heading.
 */
internal data class Standoff(
    val originGeo: LatLngAlt,
    val viewerGeo: LatLngAlt,
)

/**
 * How the solid is placed this frame. [standoff] is only set when the model is too
 * far away to be seen where it really is.
 */
internal data class FarViewPlan(
    val realDistanceM: Double?,
    val standoff: Standoff?,
) {
    val farAway: Boolean get() = standoff != null

    /** Rounded so walking a step does not recompose the HUD. */
    val roundedDistanceM: Double?
        get() = realDistanceM?.let { Math.round(it / 5.0) * 5.0 }
}

/**
 * Decides whether the model has to be pulled in towards [viewer]. Entering the far
 * view needs [FAR_VIEW_TRIGGER_M] and leaving it only [FAR_VIEW_EXIT_M], so GPS noise
 * around the threshold cannot flap between the two placements.
 */
internal fun farViewPlan(
    centroid: LatLngAlt?,
    viewer: LatLngAlt?,
    halfExtentM: Float,
    wasFarAway: Boolean,
    forceCloseUp: Boolean = false,
): FarViewPlan {
    if (centroid == null || viewer == null) return FarViewPlan(null, null)
    val distance = GeoMath.distanceMeters(viewer, centroid)
    val viewDist = viewDistanceFor(halfExtentM).toDouble()
    val threshold = if (wasFarAway) FAR_VIEW_EXIT_M else FAR_VIEW_TRIGGER_M
    val pullIn = if (forceCloseUp) distance > viewDist else distance > threshold
    if (!pullIn) return FarViewPlan(distance, null)
    return FarViewPlan(
        realDistanceM = distance,
        standoff = Standoff(
            originGeo = farViewOrigin(
                centroid = centroid,
                viewer = viewer,
                standoffM = viewDist,
            ),
            viewerGeo = viewer,
        ),
    )
}

/** Where the solid is and how big it is, for aiming the camera at it. */
internal data class SolidExtent(
    val centroid: LatLngAlt?,
    val halfExtentM: Float,
)

/**
 * The IFC local origin is often the project base point, tens of metres away from the
 * building, so a mesh document is measured from its own centre instead.
 */
internal fun solidExtentOf(document: KmzDocument, fallback: LatLngAlt?): SolidExtent {
    val ringPoints = buildList {
        document.polygons.forEach { addAll(openRing(it.ring)) }
        document.lines.forEach { addAll(it.coordinates) }
        document.points.forEach { add(it.coordinate) }
    }
    if (ringPoints.isNotEmpty()) {
        val centroid = centroidOf(ringPoints)!!
        val half = ringPoints.maxOf { GeoMath.distanceMeters(centroid, it) }
        return SolidExtent(centroid, half.toFloat().coerceAtLeast(5f))
    }

    val vertices = document.localMeshes.flatMap { it.vertices }
    val origin = document.meshOrigin
    if (vertices.isEmpty() || origin == null) {
        return SolidExtent(origin ?: fallback, 15f)
    }

    val cx = vertices.sumOf { it.x.toDouble() } / vertices.size
    val cz = vertices.sumOf { it.z.toDouble() } / vertices.size
    val half = vertices.maxOf { hypot(it.x - cx, it.z - cz) }

    // Same transform buildMeshNodes applies: yaw around up, then the ENU offset.
    val theta = Math.toRadians(document.meshRotationDeg.toDouble())
    val east = cx * cos(theta) + cz * sin(theta)
    val north = cx * sin(theta) - cz * cos(theta)
    val centroid = GeoMath.destination(
        origin = origin,
        bearingDegrees = Math.toDegrees(atan2(east, north)),
        distanceMeters = hypot(east, north),
    )
    return SolidExtent(centroid, half.toFloat().coerceAtLeast(5f))
}

/** Compass heading minus the camera's looking bearing in the AR session XZ plane. */
internal fun sessionYawDegrees(
    headingDegrees: Double,
    cameraZAxisX: Double,
    cameraZAxisZ: Double,
): Double {
    val forwardAngle = Math.toDegrees(atan2(-cameraZAxisX, cameraZAxisZ))
    return headingDegrees - forwardAngle
}

internal fun shouldAcercarByDefault(distanceM: Double): Boolean = distanceM > ACERCAR_DEFAULT_M
