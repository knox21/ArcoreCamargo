package com.arcoregeo.campoar.ui.ar

import android.graphics.Color
import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.geo.ReferenceCalibration
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
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

private val WALL_COLOR = Float4(0.98f, 0.62f, 0.22f, 1f)
private val TOP_COLOR = Float4(1f, 0.90f, 0.40f, 1f)
private val BOTTOM_COLOR = Float4(0.45f, 0.78f, 1f, 1f)

/**
 * Material instances shared by every solid of a document. They live as long as the
 * [MaterialLoader], so creating one set per rebuild would leak the previous ones.
 */
class ArSolidMaterials(loader: MaterialLoader) {
    val wall: MaterialInstance = loader.createArVisibleColor(WALL_COLOR)
    val top: MaterialInstance = loader.createArVisibleColor(TOP_COLOR)
    val bottom: MaterialInstance = loader.createArVisibleColor(BOTTOM_COLOR)
    val edgeTop: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#FDE047"))
    val edgeBottom: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#38BDF8"))
    val post: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#F8FAFC"))
    val marker: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#38BDF8"))
    val you: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#34D399"))
}

/**
 * Exact extruded prism matching the IFC/KML footprint — no axis-aligned bounding
 * box (that was distorting rotated plots). Walls + caps from the real ring,
 * plus thin colored edges so the silhouette stays readable in AR.
 *
 * [detailed] adds the edge beams and corner cubes. Buildings with many footprints
 * turn that into thousands of renderables, so callers disable it there.
 */
fun buildSolidNodes(
    engine: Engine,
    materials: ArSolidMaterials,
    ring: List<LatLngAlt>,
    calibration: ReferenceCalibration,
    heightMeters: Float,
    heightOffsetMeters: Float = 0f,
    detailed: Boolean = true,
): List<Node> {
    if (ring.size < 3) return emptyList()

    val base = ring.map { coord ->
        val enu = calibration.enuOf(coord)
        Float2(enu.east.toFloat(), (-enu.north).toFloat())
    }
    val y0 = ring.map { calibration.enuOf(it).up.toFloat() }.average().toFloat() + heightOffsetMeters
    val h = heightMeters.coerceAtLeast(0.5f)
    val yTop = y0 + h

    val nodes = mutableListOf<Node>()

    buildWallGeometry(engine, base, y0, yTop, WALL_COLOR)?.let { geometry ->
        nodes += GeometryNode(engine, geometry, materials.wall) {
            culling(false)
        }
    }

    buildCapGeometry(engine, base, yTop, Float3(0f, 1f, 0f), TOP_COLOR)?.let { geometry ->
        nodes += GeometryNode(engine, geometry, materials.top) {
            culling(false)
        }
    }
    buildCapGeometry(engine, base, y0, Float3(0f, -1f, 0f), BOTTOM_COLOR)?.let { geometry ->
        nodes += GeometryNode(engine, geometry, materials.bottom) {
            culling(false)
        }
    }

    if (!detailed) return nodes

    for (i in base.indices) {
        val a = base[i]
        val b = base[(i + 1) % base.size]
        beam(engine, nodes, a.x, y0, a.y, b.x, y0, b.y, 0.08f, materials.edgeBottom)
        beam(engine, nodes, a.x, yTop, a.y, b.x, yTop, b.y, 0.08f, materials.edgeTop)
        beam(engine, nodes, a.x, y0, a.y, a.x, yTop, a.y, 0.09f, materials.post)
        nodes += CubeNode(
            engine = engine,
            size = Size(0.22f, 0.22f, 0.22f),
            center = Position(a.x, y0 + 0.12f, a.y),
            materialInstance = materials.edgeBottom,
        )
        nodes += CubeNode(
            engine = engine,
            size = Size(0.18f, 0.18f, 0.18f),
            center = Position(a.x, yTop, a.y),
            materialInstance = materials.edgeTop,
        )
    }

    return nodes
}

/** Bright matte color that stays readable under ARCore ambient light estimation. */
fun MaterialLoader.createArVisibleColor(color: Float4): MaterialInstance {
    // Boost albedo slightly — opaque_colored has no emissive parameter (setting one crashes).
    val boosted = Float4(
        (color.x * 1.15f).coerceAtMost(1f),
        (color.y * 1.15f).coerceAtMost(1f),
        (color.z * 1.15f).coerceAtMost(1f),
        1f,
    )
    return createColorInstance(boosted, metallic = 0f, roughness = 1f, reflectance = 0f)
}

fun MaterialLoader.createArVisibleColor(colorInt: Int): MaterialInstance {
    val r = ((colorInt shr 16) and 0xFF) / 255f
    val g = ((colorInt shr 8) and 0xFF) / 255f
    val b = (colorInt and 0xFF) / 255f
    return createArVisibleColor(Float4(r, g, b, 1f))
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
    material: MaterialInstance,
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
