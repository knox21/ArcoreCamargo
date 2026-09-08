package com.arcoregeo.campoar.ui.ar

import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.geo.GeoMath

/**
 * Past this distance a georeferenced model is a couple of pixels tall and GPS noise
 * is larger than the model itself, so it is drawn closer than it really is.
 */
internal const val FAR_VIEW_TRIGGER_M = 100.0

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
