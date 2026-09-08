package com.arcoregeo.campoar.ui.ifc

import io.github.sceneview.math.Position
import kotlin.math.max
import kotlin.math.sqrt

/** Orbit camera placement and clipping planes for a model of a given size. */
internal data class ViewerFraming(
    val cameraPosition: Position,
    val near: Float,
    val far: Float,
)

/**
 * SceneView's camera ships with a 30 m far plane, so a building was framed from
 * outside its own frustum and the screen stayed empty. The planes are derived from
 * the model size instead, with room for the far corner.
 */
internal fun framingFor(spanMeters: Float): ViewerFraming {
    val span = if (spanMeters.isFinite()) spanMeters.coerceIn(1f, 100_000f) else 10f
    val distance = span * 1.8f
    val position = Position(distance * 0.7f, distance * 0.55f, distance)
    val radius = sqrt(
        position.x * position.x + position.y * position.y + position.z * position.z,
    )
    return ViewerFraming(
        cameraPosition = position,
        near = (radius / 1000f).coerceIn(0.05f, 5f),
        far = max(60f, (radius + span) * 3f),
    )
}
