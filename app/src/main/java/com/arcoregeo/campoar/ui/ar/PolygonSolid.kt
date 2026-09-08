package com.arcoregeo.campoar.ui.ar

import android.graphics.Color
import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.geo.ReferenceCalibration
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.GeometryNode
import io.github.sceneview.node.Node
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Exact extruded prism matching the IFC/KML footprint — no axis-aligned bounding
 * box (that was distorting rotated plots). Walls + caps from the real ring,
 * plus thin colored edges so the silhouette stays readable in AR.
 */
fun buildSolidNodes(
    engine: Engine,
    materialLoader: MaterialLoader,
    ring: List<LatLngAlt>,
    calibration: ReferenceCalibration,
    heightMeters: Float,
    heightOffsetMeters: Float = 0f,
): List<Node> {
    if (ring.size < 3) return emptyList()

    val base = ring.map { coord ->
        val enu = calibration.enuOf(coord)
        Float2(enu.east.toFloat(), (-enu.north).toFloat())
    }
    val y0 = ring.map { calibration.enuOf(it).up.toFloat() }.average().toFloat() + heightOffsetMeters
    val h = heightMeters.coerceAtLeast(0.5f)
    val yTop = y0 + h

    val wallColor = Float4(0.98f, 0.55f, 0.18f, 1f)
    val topColor = Float4(1f, 0.85f, 0.35f, 1f)
    val bottomColor = Float4(0.55f, 0.75f, 0.95f, 1f)

    val wallMat = materialLoader.createColorInstance(wallColor, metallic = 0f, roughness = 0.55f, reflectance = 0.2f)
    val topMat = materialLoader.createColorInstance(topColor, metallic = 0f, roughness = 0.45f, reflectance = 0.25f)
    val bottomMat = materialLoader.createColorInstance(bottomColor, metallic = 0f, roughness = 0.5f, reflectance = 0.2f)
    val edgeTopMat = materialLoader.createColorInstance(Color.parseColor("#FDE047"))
    val edgeBottomMat = materialLoader.createColorInstance(Color.parseColor("#38BDF8"))
    val postMat = materialLoader.createColorInstance(Color.parseColor("#F8FAFC"))

    val nodes = mutableListOf<Node>()

    // Exact walls (one quad per edge) — same footprint as the IFC polyline.
    buildWallGeometry(engine, base, y0, yTop, wallColor)?.let { geometry ->
        nodes += GeometryNode(engine, geometry, wallMat) {
            culling(false)
        }
    }

    // Exact top / bottom caps (fan triangulation of the real ring).
    buildCapGeometry(engine, base, yTop, Float3(0f, 1f, 0f), topColor)?.let { geometry ->
        nodes += GeometryNode(engine, geometry, topMat) {
            culling(false)
        }
    }
    buildCapGeometry(engine, base, y0, Float3(0f, -1f, 0f), bottomColor)?.let { geometry ->
        nodes += GeometryNode(engine, geometry, bottomMat) {
            culling(false)
        }
    }

    // Bright edges so the IFC outline is obvious even in dim AR light.
    for (i in base.indices) {
        val a = base[i]
        val b = base[(i + 1) % base.size]
        beam(engine, nodes, a.x, y0, a.y, b.x, y0, b.y, 0.08f, edgeBottomMat)
        beam(engine, nodes, a.x, yTop, a.y, b.x, yTop, b.y, 0.08f, edgeTopMat)
        beam(engine, nodes, a.x, y0, a.y, a.x, yTop, a.y, 0.09f, postMat)
        nodes += CubeNode(
            engine = engine,
            size = Size(0.22f, 0.22f, 0.22f),
            center = Position(a.x, y0 + 0.12f, a.y),
            materialInstance = edgeBottomMat,
        )
        nodes += CubeNode(
            engine = engine,
            size = Size(0.18f, 0.18f, 0.18f),
            center = Position(a.x, yTop, a.y),
            materialInstance = edgeTopMat,
        )
    }

    return nodes
}

private fun buildWallGeometry(
    engine: Engine,
    base: List<Float2>,
    y0: Float,
    yTop: Float,
    color: Float4,
): Geometry? {
    if (base.size < 3) return null
    val vertices = mutableListOf<Geometry.Vertex>()
    val indices = mutableListOf<Int>()

    for (i in base.indices) {
        val a = base[i]
        val b = base[(i + 1) % base.size]
        val ex = b.x - a.x
        val ez = b.y - a.y
        val len = sqrt(ex * ex + ez * ez)
        if (len < 1e-4f) continue
        // Outward-ish normal in XZ (double-sided indices make orientation safe).
        val nx = ez / len
        val nz = -ex / len
        val normal = Float3(nx, 0f, nz)
        val start = vertices.size
        vertices += Geometry.Vertex(Float3(a.x, y0, a.y), normal, Float2(0f, 0f), color)
        vertices += Geometry.Vertex(Float3(b.x, y0, b.y), normal, Float2(1f, 0f), color)
        vertices += Geometry.Vertex(Float3(b.x, yTop, b.y), normal, Float2(1f, 1f), color)
        vertices += Geometry.Vertex(Float3(a.x, yTop, a.y), normal, Float2(0f, 1f), color)
        indices += listOf(start, start + 1, start + 2, start, start + 2, start + 3)
        indices += listOf(start, start + 2, start + 1, start, start + 3, start + 2)
    }
    if (vertices.isEmpty()) return null
    return Geometry.Builder().vertices(vertices).indices(indices).build(engine)
}

private fun buildCapGeometry(
    engine: Engine,
    base: List<Float2>,
    y: Float,
    normal: Float3,
    color: Float4,
): Geometry? {
    if (base.size < 3) return null
    val cx = base.map { it.x }.average().toFloat()
    val cz = base.map { it.y }.average().toFloat()
    val vertices = mutableListOf<Geometry.Vertex>()
    val indices = mutableListOf<Int>()
    vertices += Geometry.Vertex(Float3(cx, y, cz), normal, Float2(0.5f, 0.5f), color)
    base.forEach { p ->
        vertices += Geometry.Vertex(Float3(p.x, y, p.y), normal, Float2(0f, 0f), color)
    }
    for (i in base.indices) {
        val i0 = 1 + i
        val i1 = 1 + (i + 1) % base.size
        indices += listOf(0, i0, i1)
        indices += listOf(0, i1, i0)
    }
    return Geometry.Builder().vertices(vertices).indices(indices).build(engine)
}

private fun beam(
    engine: Engine,
    nodes: MutableList<Node>,
    ax: Float,
    ay: Float,
    az: Float,
    bx: Float,
    by: Float,
    bz: Float,
    thickness: Float,
    material: com.google.android.filament.MaterialInstance,
) {
    val dx = bx - ax
    val dy = by - ay
    val dz = bz - az
    val len = sqrt(dx * dx + dy * dy + dz * dz)
    if (len < 0.04f) return
    val node = CubeNode(
        engine = engine,
        size = Size(thickness, thickness, len),
        center = Position((ax + bx) / 2f, (ay + by) / 2f, (az + bz) / 2f),
        materialInstance = material,
    )
    val yaw = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
    val horizontal = sqrt(dx * dx + dz * dz)
    val pitch = -Math.toDegrees(atan2(dy.toDouble(), horizontal.toDouble())).toFloat()
    node.rotation = Position(pitch, yaw, 0f)
    nodes += node
}
