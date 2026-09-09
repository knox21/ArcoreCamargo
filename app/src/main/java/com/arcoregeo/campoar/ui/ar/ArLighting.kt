package com.arcoregeo.campoar.ui.ar

import com.google.android.filament.IndirectLight
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARSceneView

/**
 * Neutral indoor/outdoor irradiance (the same spherical harmonics SceneView ships
 * for its default 3D environment), so an AR model is lit like it is in the viewer.
 */
private val NEUTRAL_IRRADIANCE = floatArrayOf(
    0.976625f, 0.976625f, 0.976625f,
    0.552806f, 0.552806f, 0.552806f,
    0.022355f, 0.022355f, 0.022355f,
    -0.031812f, -0.031812f, -0.031812f,
    -0.059246f, -0.059246f, -0.059246f,
    0.062930f, 0.062930f, 0.062930f,
    0.027763f, 0.027763f, 0.027763f,
    0.177627f, 0.177627f, 0.177627f,
    0.086374f, 0.086374f, 0.086374f,
)

private const val MAIN_LIGHT_INTENSITY = 100_000f

/**
 * ARCore light estimation is deliberately off: SceneView feeds the estimate back
 * into the same light node it reads from, so the intensity is multiplied by a
 * factor below 1 on every frame and the model fades to black within seconds.
 * Fixed lighting keeps the solid readable outdoors and indoors.
 */
fun ARSceneView.applyFixedLighting() {
    lightEstimator?.isEnabled = false
    indirectLight = IndirectLight.Builder()
        .irradiance(3, NEUTRAL_IRRADIANCE)
        .intensity(30_000f)
        .build(engine)
    mainLightNode?.apply {
        intensity = MAIN_LIGHT_INTENSITY
        lightDirection = Float3(0.55f, -0.72f, -0.42f)
    }
}
