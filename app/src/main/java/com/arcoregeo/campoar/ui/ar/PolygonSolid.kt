package com.arcoregeo.campoar.ui.ar

import android.graphics.Color
import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.data.LocalMesh
import com.arcoregeo.campoar.data.MeshShading
import com.arcoregeo.campoar.data.Vec3f
import com.arcoregeo.campoar.data.edgeRibbons
import com.arcoregeo.campoar.data.openRing
import com.arcoregeo.campoar.data.shadingOf
import com.arcoregeo.campoar.geo.GeoMath
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private val WALL_COLOR = Float4(0.98f, 0.62f, 0.22f, 1f)
private val TOP_COLOR = Float4(1f, 0.90f, 0.40f, 1f)
private val BOTTOM_COLOR = Float4(0.45f, 0.78f, 1f, 1f)
private val OUTLINE_COLOR = Float4(0.07f, 0.08f, 0.10f, 1f)

/**
 * Material instances shared by every solid of a document. They live as long as the
 * [MaterialLoader], so creating one set per rebuild would leak the previous ones.
 */
class ArSolidMaterials(loader: MaterialLoader) {
    val wall: MaterialInstance = loader.createArShadedColor(WALL_COLOR)
    val top: MaterialInstance = loader.createArShadedColor(TOP_COLOR)
    val bottom: MaterialInstance = loader.createArShadedColor(BOTTOM_COLOR)
    val edgeTop: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#FDE047"))
    val edgeBottom: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#38BDF8"))
    val post: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#F8FAFC"))
    val marker: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#38BDF8"))
    val you: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#34D399"))
    val outline: MaterialInstance = loader.createArVisibleColor(OUTLINE_COLOR)
    val kmlFill: MaterialInstance = loader.createArShadedColor(Float4(0.28f, 0.78f, 0.96f, 1f))
    val kmlLine: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#F472B6"))
    val bubblePoint: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#22D3EE"))
    val bubbleVertex: MaterialInstance = loader.createArVisibleColor(Color.parseColor("#FBBF24"))
}

/** Lit colour so walls, roofs and floors read as different faces under the AR light. */
fun MaterialLoader.createArShadedColor(color: Float4): MaterialInstance {
    val boosted = Float4(
        (color.x * 1.08f).coerceAtMost(1f),
        (color.y * 1.08f).coerceAtMost(1f),
        (color.z * 1.08f).coerceAtMost(1f),
        1f,
    )
    return createColorInstance(boosted, metallic = 0.04f, roughness = 0.42f, reflectance = 0.22f)
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

/**
 * Body and outline as two node lists so the camera can hide the edges without
 * throwing the solid away and building it again (that is the hitch on the button).
 */
class ArMeshNodes(
    val bodies: List<Node>,
    val edges: List<Node>,
) {
    val all: List<Node> get() = bodies + edges

    fun showEdges(visible: Boolean) {
        edges.forEach { it.isVisible = visible }
    }
}

/**
 * Places the IFC mesh itself in AR, so the camera shows the same model as the 3D
 * viewer instead of a box around the footprint.
 *
 * Mesh coordinates are metres in the model local frame (Y up, Z = −north before
 * rotation). Call [relocateMeshNodes] to sit that frame on Earth; the geometry is
 * built once so a GPS re-anchor does not rebuild Filament buffers.
 *
 * Faces are always split into walls / slabs with outward normals. The outline is
 * a triangle ribbon kept on the side and only shown when asked.
 */
fun buildMeshNodes(
    engine: Engine,
    materials: ArSolidMaterials,
    meshes: List<LocalMesh>,
    outlines: List<List<Int>>? = null,
    looks: List<MeshShading>? = null,
): ArMeshNodes {
    val bodies = mutableListOf<Node>()
    val edges = mutableListOf<Node>()
    if (meshes.isEmpty()) return ArMeshNodes(bodies, edges)
    meshes.forEachIndexed { index, mesh ->
        val shading = looks?.getOrNull(index) ?: runCatching { shadingOf(mesh) }.getOrNull()
        if (shading != null) {
            listOf(
                shading.walls to materials.wall,
                shading.tops to materials.top,
                shading.bottoms to materials.bottom,
            ).forEach { (indices, material) ->
                meshGeometry(engine, mesh, shading, indices, material)?.let { bodies += it }
            }
        } else {
            val geometry = buildMeshGeometry(engine, mesh, MESH_COLORS[index % MESH_COLORS.size])
                ?: return@forEachIndexed
            bodies += GeometryNode(engine, geometry, materials.wall) {
                culling(false)
            }
        }
        if (outlines != null) {
            ribbonNode(engine, materials.outline, mesh, outlines.getOrNull(index).orEmpty())
                ?.let { edges += it }
        }
    }
    return ArMeshNodes(bodies, edges)
}

fun relocateMeshNodes(
    nodes: List<Node>,
    calibration: ReferenceCalibration,
    meshOrigin: LatLngAlt?,
    rotationDeg: Float,
    heightOffsetMeters: Float = 0f,
    yawOffsetDeg: Float = 0f,
) {
    val offset = meshOrigin?.let { calibration.enuOf(it) }
    val east0 = offset?.east ?: 0.0
    val north0 = offset?.north ?: 0.0
    val (east, north) = GeoMath.rotateYaw(east0, north0, yawOffsetDeg.toDouble())
    val up = (offset?.up?.toFloat() ?: 0f) + heightOffsetMeters
    val place = Position(east.toFloat(), up, (-north).toFloat())
    val turn = Position(0f, rotationDeg + yawOffsetDeg, 0f)
    nodes.forEach { it.stand(place, turn) }
}

private val MESH_COLORS = listOf(
    Float4(0.98f, 0.62f, 0.22f, 1f),
    Float4(1f, 0.90f, 0.40f, 1f),
    Float4(0.45f, 0.78f, 1f, 1f),
)

private fun Node.stand(place: Position, turn: Position): Node = apply {
    position = place
    rotation = turn
}

private fun buildMeshGeometry(engine: Engine, mesh: LocalMesh, color: Float4): Geometry? {
    if (mesh.vertices.isEmpty() || mesh.indices.size < 3) return null

    // Filament indexes natively; an out-of-range index takes the process down.
    val indices = ArrayList<Int>(mesh.indices.size)
    val normals = Array(mesh.vertices.size) { Float3(0f, 0f, 0f) }
    var t = 0
    while (t + 2 < mesh.indices.size) {
        val ia = mesh.indices[t]
        val ib = mesh.indices[t + 1]
        val ic = mesh.indices[t + 2]
        if (ia in mesh.vertices.indices && ib in mesh.vertices.indices && ic in mesh.vertices.indices) {
            indices += ia
            indices += ib
            indices += ic
            val a = mesh.vertices[ia]
            val b = mesh.vertices[ib]
            val c = mesh.vertices[ic]
            val n = Float3(
                (b.y - a.y) * (c.z - a.z) - (b.z - a.z) * (c.y - a.y),
                (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z),
                (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x),
            )
            normals[ia] = Float3(normals[ia].x + n.x, normals[ia].y + n.y, normals[ia].z + n.z)
            normals[ib] = Float3(normals[ib].x + n.x, normals[ib].y + n.y, normals[ib].z + n.z)
            normals[ic] = Float3(normals[ic].x + n.x, normals[ic].y + n.y, normals[ic].z + n.z)
        }
        t += 3
    }
    if (indices.isEmpty()) return null

    val vertices = mesh.vertices.mapIndexed { i, v ->
        val n = normals[i]
        val len = sqrt(n.x * n.x + n.y * n.y + n.z * n.z)
        val normal = if (len < 1e-6f || !len.isFinite()) {
            Float3(0f, 1f, 0f)
        } else {
            Float3(n.x / len, n.y / len, n.z / len)
        }
        Geometry.Vertex(Float3(v.x, v.y, v.z), normal, Float2(0f, 0f), color)
    }
    return Geometry.Builder().vertices(vertices).indices(indices).build(engine)
}

private fun meshGeometry(
    engine: Engine,
    mesh: LocalMesh,
    shading: MeshShading,
    indices: List<Int>,
    material: MaterialInstance,
): GeometryNode? {
    if (indices.size < 3) return null
    val vertices = mesh.vertices.mapIndexed { i, v ->
        Geometry.Vertex(
            Float3(v.x, v.y, v.z),
            Float3(shading.normals[i * 3], shading.normals[i * 3 + 1], shading.normals[i * 3 + 2]),
            Float2(0f, 0f),
            WALL_COLOR,
        )
    }
    val geometry = Geometry.Builder().vertices(vertices).indices(indices).build(engine)
    return GeometryNode(engine, geometry, material) {
        culling(false)
    }
}

private fun ribbonNode(
    engine: Engine,
    material: MaterialInstance,
    mesh: LocalMesh,
    edges: List<Int>,
): GeometryNode? {
    val ribbon = edgeRibbons(mesh, edges, halfWidth = ribbonWidthOf(mesh)) ?: return null
    val geometry = buildMeshGeometry(engine, ribbon, OUTLINE_COLOR) ?: return null
    return GeometryNode(engine, geometry, material) {
        culling(false)
    }
}

private fun ribbonWidthOf(mesh: LocalMesh): Float {
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var minZ = Float.MAX_VALUE
    var maxZ = -Float.MAX_VALUE
    mesh.vertices.forEach { v ->
        if (!v.x.isFinite()) return@forEach
        if (v.x < minX) minX = v.x
        if (v.x > maxX) maxX = v.x
        if (v.y < minY) minY = v.y
        if (v.y > maxY) maxY = v.y
        if (v.z < minZ) minZ = v.z
        if (v.z > maxZ) maxZ = v.z
    }
    val span = maxOf(maxX - minX, maxY - minY, maxZ - minZ)
    return if (span.isFinite() && span > 0f) (span * 0.009f).coerceIn(0.05f, 0.22f) else 0.06f
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

private const val MAX_KML_POLYGONS = 40
private const val MAX_KML_LINES = 40
private const val MAX_KML_BUBBLES = 80
private const val KML_SURFACE_LIFT_M = 0.04f

private val KML_FILL_COLOR = Float4(0.28f, 0.78f, 0.96f, 1f)
private val KML_LINE_COLOR = Float4(0.96f, 0.45f, 0.71f, 1f)
private val BUBBLE_POINT_COLOR = Float4(0.13f, 0.83f, 0.93f, 1f)
private val BUBBLE_VERTEX_COLOR = Float4(0.98f, 0.75f, 0.14f, 1f)

/**
 * KMZ/KML overlay: a polygon is a flat filled surface, a line is a line, and
 * every vertex gets a bubble so you can match GPS and compass. No extruded posts.
 */
fun buildKmlOverlayNodes(
    engine: Engine,
    materials: ArSolidMaterials,
    document: KmzDocument,
    calibration: ReferenceCalibration,
    heightOffsetMeters: Float = 0f,
    yawOffsetDeg: Float = 0f,
    includeFeatures: Boolean = true,
    includeBubbles: Boolean = true,
    bubbleSpanM: Float = 20f,
): List<Node> {
    val nodes = mutableListOf<Node>()
    if (includeFeatures) {
        document.polygons.take(MAX_KML_POLYGONS).forEach { polygon ->
            val ring = openRing(polygon.ring).map { coord ->
                enuPosition(coord, calibration, heightOffsetMeters, yawOffsetDeg)
            }
            kmlPolygonNodes(engine, materials, ring).let { nodes += it }
        }
        document.lines.take(MAX_KML_LINES).forEach { line ->
            val path = line.coordinates.map { coord ->
                enuPosition(coord, calibration, heightOffsetMeters, yawOffsetDeg)
            }
            kmlLineNode(engine, materials, path, bubbleSpanM)?.let { nodes += it }
        }
    }
    if (includeBubbles) {
        val radius = bubbleRadiusM(bubbleSpanM)
        document.points.take(MAX_KML_BUBBLES).forEach { point ->
            val at = enuPosition(point.coordinate, calibration, heightOffsetMeters, yawOffsetDeg)
            bubbleNode(engine, materials.bubblePoint, at, radius, BUBBLE_POINT_COLOR)?.let { nodes += it }
        }
        val remaining = (MAX_KML_BUBBLES - document.points.size).coerceAtLeast(0)
        if (remaining > 0) {
            val vertices = document.polygonVertices() + document.lineVertices()
            vertices.take(remaining).forEach { point ->
                val at = enuPosition(point.coordinate, calibration, heightOffsetMeters, yawOffsetDeg)
                bubbleNode(engine, materials.bubbleVertex, at, radius * 0.82f, BUBBLE_VERTEX_COLOR)
                    ?.let { nodes += it }
            }
        }
    }
    return nodes
}

internal fun enuPosition(
    coord: LatLngAlt,
    calibration: ReferenceCalibration,
    heightOffsetMeters: Float,
    yawOffsetDeg: Float,
): Float3 {
    val enu = calibration.enuOf(coord)
    val (east, north) = GeoMath.rotateYaw(enu.east, enu.north, yawOffsetDeg.toDouble())
    return Float3(
        east.toFloat(),
        enu.up.toFloat() + heightOffsetMeters,
        (-north).toFloat(),
    )
}

internal fun bubbleRadiusM(spanM: Float): Float =
    (spanM * 0.04f).coerceIn(0.22f, 0.7f)

internal fun polylineRibbonMesh(points: List<Float3>, halfWidth: Float): LocalMesh? {
    if (points.size < 2) return null
    val vertices = points.map { Vec3f(it.x, it.y, it.z) }
    val edges = ArrayList<Int>((points.size - 1) * 2)
    for (i in 0 until points.lastIndex) {
        edges += i
        edges += i + 1
    }
    return edgeRibbons(LocalMesh("kml-line", vertices, emptyList()), edges, halfWidth)
}

private fun kmlPolygonNodes(
    engine: Engine,
    materials: ArSolidMaterials,
    ring: List<Float3>,
): List<Node> {
    if (ring.size < 3) return emptyList()
    val nodes = mutableListOf<Node>()
    val y = ring.map { it.y }.average().toFloat() + KML_SURFACE_LIFT_M
    val base = ring.map { Float2(it.x, it.z) }
    buildCapGeometry(engine, base, y, Float3(0f, 1f, 0f), KML_FILL_COLOR)?.let { geometry ->
        nodes += GeometryNode(engine, geometry, materials.kmlFill) {
            culling(false)
        }
    }
    val outline = ring.map { Float3(it.x, y, it.z) } + Float3(ring.first().x, y, ring.first().z)
    val span = ringSpanM(ring)
    val ribbon = polylineRibbonMesh(outline, (span * 0.012f).coerceIn(0.06f, 0.22f))
    if (ribbon != null) {
        buildMeshGeometry(engine, ribbon, OUTLINE_COLOR)?.let { geometry ->
            nodes += GeometryNode(engine, geometry, materials.outline) {
                culling(false)
            }
        }
    }
    return nodes
}

private fun kmlLineNode(
    engine: Engine,
    materials: ArSolidMaterials,
    path: List<Float3>,
    spanM: Float,
): Node? {
    val ribbon = polylineRibbonMesh(path, (spanM * 0.018f).coerceIn(0.08f, 0.28f)) ?: return null
    val geometry = buildMeshGeometry(engine, ribbon, KML_LINE_COLOR) ?: return null
    return GeometryNode(engine, geometry, materials.kmlLine) {
        culling(false)
    }
}

private fun bubbleNode(
    engine: Engine,
    material: MaterialInstance,
    at: Float3,
    radius: Float,
    color: Float4,
): Node? {
    val geometry = sphereGeometry(engine, at.x, at.y + radius, at.z, radius, color) ?: return null
    return GeometryNode(engine, geometry, material) {
        culling(false)
    }
}

private fun ringSpanM(points: List<Float3>): Float {
    if (points.isEmpty()) return 8f
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minZ = Float.MAX_VALUE
    var maxZ = -Float.MAX_VALUE
    points.forEach { p ->
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.z < minZ) minZ = p.z
        if (p.z > maxZ) maxZ = p.z
    }
    val span = maxOf(maxX - minX, maxZ - minZ)
    return if (span.isFinite() && span > 0f) span else 8f
}

private fun sphereGeometry(
    engine: Engine,
    cx: Float,
    cy: Float,
    cz: Float,
    radius: Float,
    color: Float4,
): Geometry? {
    val slices = 10
    val stacks = 6
    val vertices = ArrayList<Geometry.Vertex>((slices + 1) * (stacks + 1))
    val indices = ArrayList<Int>(slices * stacks * 12)
    for (lat in 0..stacks) {
        val theta = Math.PI * lat / stacks
        val sinT = sin(theta).toFloat()
        val cosT = cos(theta).toFloat()
        for (lon in 0..slices) {
            val phi = 2.0 * Math.PI * lon / slices
            val x = sinT * cos(phi).toFloat()
            val z = sinT * sin(phi).toFloat()
            val n = Float3(x, cosT, z)
            vertices += Geometry.Vertex(
                Float3(cx + x * radius, cy + cosT * radius, cz + z * radius),
                n,
                Float2(lon.toFloat() / slices, lat.toFloat() / stacks),
                color,
            )
        }
    }
    val cols = slices + 1
    for (lat in 0 until stacks) {
        for (lon in 0 until slices) {
            val a = lat * cols + lon
            val b = a + cols
            indices += listOf(a, b, a + 1, a + 1, b, b + 1)
            indices += listOf(a, a + 1, b, a + 1, b + 1, b)
        }
    }
    if (vertices.isEmpty() || indices.size < 3) return null
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
