package com.arcoregeo.campoar.data

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** Vertex and triangle numbers travel three to a Long, so each gets a fifth of it. */
private const val INDEX_BITS = 20
private const val INDEX_MASK = (1L shl INDEX_BITS) - 1L

/** Above this a wireframe costs more to draw than the model it outlines. */
private const val MAX_EDGE_SEGMENTS = 150_000

/** Faces meeting at less than this angle are the same surface, not a corner. */
private val CREASE_COS = cos(Math.toRadians(20.0)).toFloat()

/** Coincident vertices come in small groups; a huge one is a hash pile-up. */
private const val MAX_WELD_GROUP = 64

/** How level a face has to be to count as a slab rather than a wall. */
private const val FLAT_LIMIT = 0.7f

/**
 * Per-vertex normals plus the triangles of a solid grouped by the way they face, so
 * a building can be drawn as walls and slabs instead of one flat colour.
 */
class MeshShading(
    /** Three floats per vertex, pointing out of the solid. */
    val normals: FloatArray,
    val walls: List<Int>,
    val tops: List<Int>,
    val bottoms: List<Int>,
)

/**
 * Reads [mesh] the way a renderer needs it.
 *
 * Every triangle arrives mirrored back to front so both sides of a wall show up.
 * Averaged into vertex normals the two copies cancel each other out, which is what
 * left every normal pointing straight up and the whole building lit in a single flat
 * shade. Normals are therefore taken from one copy of each face, turned outwards.
 */
fun shadingOf(mesh: LocalMesh): MeshShading {
    val vertexCount = mesh.vertices.size
    val walls = ArrayList<Int>()
    val tops = ArrayList<Int>()
    val bottoms = ArrayList<Int>()
    val normals = FloatArray(vertexCount * 3)
    val faces = singleSidedFaces(mesh)
    if (faces.count == 0) return MeshShading(normals, walls, tops, bottoms)

    val midY = midHeightOf(mesh)
    var t = 0
    while (t + 2 < mesh.indices.size) {
        val i0 = mesh.indices[t]
        val i1 = mesh.indices[t + 1]
        val i2 = mesh.indices[t + 2]
        t += 3
        if (i0 !in 0 until vertexCount) continue
        if (i1 !in 0 until vertexCount) continue
        if (i2 !in 0 until vertexCount) continue
        val a = mesh.vertices[i0]
        val b = mesh.vertices[i1]
        val c = mesh.vertices[i2]
        val level = abs(levelnessOf(a, b, c)) > FLAT_LIMIT
        val target = when {
            !level -> walls
            (a.y + b.y + c.y) / 3f >= midY -> tops
            else -> bottoms
        }
        target += i0
        target += i1
        target += i2
    }

    // An inside-out solid has every face pointing into itself, and the whole model
    // then shades as if it stood in shadow. Its volume comes out negative.
    val turn = if (faces.volume < 0.0) -1f else 1f
    for (f in 0 until faces.count) {
        val nx = faces.normals[f * 3] * turn
        val ny = faces.normals[f * 3 + 1] * turn
        val nz = faces.normals[f * 3 + 2] * turn
        for (corner in 0 until 3) {
            val v = faces.indices[f * 3 + corner] * 3
            normals[v] += nx
            normals[v + 1] += ny
            normals[v + 2] += nz
        }
    }
    for (v in 0 until vertexCount) {
        val x = normals[v * 3]
        val y = normals[v * 3 + 1]
        val z = normals[v * 3 + 2]
        val len = sqrt(x * x + y * y + z * z)
        if (len < 1e-6f) {
            normals[v * 3 + 1] = 1f
        } else {
            normals[v * 3] = x / len
            normals[v * 3 + 1] = y / len
            normals[v * 3 + 2] = z / len
        }
    }
    return MeshShading(normals, walls, tops, bottoms)
}

/**
 * Index pairs for the lines an architect would draw on [mesh]: its open borders and
 * every crease where two faces meet at an angle. Drawing all the triangle edges
 * instead also draws the diagonal splitting each rectangular face in two, and a
 * facade then reads as noise.
 *
 * Faces are welded by position first. Each one carries a private copy of its
 * corners, so without welding no edge is ever shared and the model looks like a pile
 * of loose quads.
 */
fun featureEdges(mesh: LocalMesh): List<Int> {
    val vertexCount = mesh.vertices.size
    if (vertexCount < 3 || vertexCount > INDEX_MASK) return emptyList()
    val faces = singleSidedFaces(mesh)
    if (faces.count == 0 || faces.count > INDEX_MASK) return emptyList()

    val canonical = weldByPosition(mesh.vertices)
    val edges = LongArray(faces.count * 3)
    var count = 0
    for (f in 0 until faces.count) {
        val c0 = canonical[faces.indices[f * 3]]
        val c1 = canonical[faces.indices[f * 3 + 1]]
        val c2 = canonical[faces.indices[f * 3 + 2]]
        if (c0 != c1) edges[count++] = edgeKey(c0, c1, f)
        if (c1 != c2) edges[count++] = edgeKey(c1, c2, f)
        if (c2 != c0) edges[count++] = edgeKey(c2, c0, f)
    }
    if (count == 0) return emptyList()
    Arrays.sort(edges, 0, count)

    val out = ArrayList<Int>(1_024)
    var i = 0
    while (i < count) {
        val pair = edges[i] ushr INDEX_BITS
        var j = i + 1
        while (j < count && (edges[j] ushr INDEX_BITS) == pair) j++
        if (isOutline(edges, i, j, faces.normals)) {
            out += (pair ushr INDEX_BITS).toInt()
            out += (pair and INDEX_MASK).toInt()
            if (out.size >= MAX_EDGE_SEGMENTS * 2) return out
        }
        i = j
    }
    return out
}

/**
 * Outlines are sorted over every edge of the document, and AR rebuilds its nodes
 * whenever you walk a few metres, so the result is worked out once and kept.
 */
class MeshEdgeCache(private val meshes: List<LocalMesh>) {
    private var cached: List<List<Int>>? = null

    fun edges(): List<List<Int>> = cached ?: meshes.map(::featureEdges).also { cached = it }
}

/**
 * Turns each outline segment into two thin quads, a cross along the edge, so it can
 * be drawn with the same triangle material as the solid. Filament's lit materials
 * on Android do not carry a LINES shader, and asking for one takes the process down.
 */
fun edgeRibbons(mesh: LocalMesh, edges: List<Int>, halfWidth: Float): LocalMesh? {
    if (edges.size < 2 || mesh.vertices.isEmpty()) return null
    val width = if (halfWidth.isFinite() && halfWidth > 0f) halfWidth else 0.04f
    val vertices = ArrayList<Vec3f>(edges.size * 4)
    val indices = ArrayList<Int>(edges.size * 12)
    var i = 0
    while (i + 1 < edges.size) {
        val ia = edges[i]
        val ib = edges[i + 1]
        i += 2
        if (ia !in mesh.vertices.indices || ib !in mesh.vertices.indices) continue
        val a = mesh.vertices[ia]
        val b = mesh.vertices[ib]
        if (!a.x.isFinite() || !b.x.isFinite()) continue
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1e-4f) continue
        val ux = dx / len
        val uy = dy / len
        val uz = dz / len
        var vx = uz
        var vy = 0f
        var vz = -ux
        var vLen = sqrt(vx * vx + vy * vy + vz * vz)
        if (vLen < 0.1f) {
            vx = 0f
            vy = uz
            vz = -uy
            vLen = sqrt(vx * vx + vy * vy + vz * vz)
        }
        if (vLen < 1e-6f) continue
        vx = vx / vLen * width
        vy = vy / vLen * width
        vz = vz / vLen * width
        val wx = (uy * vz - uz * vy)
        val wy = (uz * vx - ux * vz)
        val wz = (ux * vy - uy * vx)
        appendRibbonQuad(vertices, indices, a, b, vx, vy, vz)
        appendRibbonQuad(vertices, indices, a, b, wx, wy, wz)
    }
    if (indices.size < 3) return null
    return LocalMesh("aristas", vertices, indices)
}

private fun appendRibbonQuad(
    vertices: MutableList<Vec3f>,
    indices: MutableList<Int>,
    a: Vec3f,
    b: Vec3f,
    px: Float,
    py: Float,
    pz: Float,
) {
    val start = vertices.size
    vertices += Vec3f(a.x - px, a.y - py, a.z - pz)
    vertices += Vec3f(b.x - px, b.y - py, b.z - pz)
    vertices += Vec3f(b.x + px, b.y + py, b.z + pz)
    vertices += Vec3f(a.x + px, a.y + py, a.z + pz)
    indices += start
    indices += start + 1
    indices += start + 2
    indices += start
    indices += start + 2
    indices += start + 3
}

/** One copy of each in-range triangle, with its unit normal and the volume they enclose. */
private class Faces(
    val indices: IntArray,
    val normals: FloatArray,
    val count: Int,
    val volume: Double,
)

private fun singleSidedFaces(mesh: LocalMesh): Faces {
    val vertexCount = mesh.vertices.size
    val triangleCount = mesh.indices.size / 3
    if (vertexCount == 0 || triangleCount == 0) {
        return Faces(IntArray(0), FloatArray(0), 0, 0.0)
    }
    val indices = IntArray(triangleCount * 3)
    val normals = FloatArray(triangleCount * 3)
    val packable = vertexCount <= INDEX_MASK
    val seen = if (packable) HashSet<Long>(triangleCount) else null
    var count = 0
    var volume = 0.0

    for (t in 0 until triangleCount) {
        val i0 = mesh.indices[t * 3]
        val i1 = mesh.indices[t * 3 + 1]
        val i2 = mesh.indices[t * 3 + 2]
        if (i0 !in 0 until vertexCount) continue
        if (i1 !in 0 until vertexCount) continue
        if (i2 !in 0 until vertexCount) continue
        // The mirrored copy of a face carries the very same corners in reverse.
        if (seen != null && !seen.add(faceKey(i0, i1, i2))) continue

        val a = mesh.vertices[i0]
        val b = mesh.vertices[i1]
        val c = mesh.vertices[i2]
        val nx = (b.y - a.y) * (c.z - a.z) - (b.z - a.z) * (c.y - a.y)
        val ny = (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z)
        val nz = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (!len.isFinite() || len < 1e-9f) continue

        indices[count * 3] = i0
        indices[count * 3 + 1] = i1
        indices[count * 3 + 2] = i2
        normals[count * 3] = nx / len
        normals[count * 3 + 1] = ny / len
        normals[count * 3 + 2] = nz / len
        count++
        volume += a.x.toDouble() * (b.y.toDouble() * c.z - b.z.toDouble() * c.y) +
            a.y.toDouble() * (b.z.toDouble() * c.x - b.x.toDouble() * c.z) +
            a.z.toDouble() * (b.x.toDouble() * c.y - b.y.toDouble() * c.x)
    }
    return Faces(indices, normals, count, volume)
}

private fun midHeightOf(mesh: LocalMesh): Float {
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    mesh.vertices.forEach { v ->
        if (!v.y.isFinite()) return@forEach
        if (v.y < minY) minY = v.y
        if (v.y > maxY) maxY = v.y
    }
    return if (minY <= maxY) (minY + maxY) / 2f else 0f
}

/** How close to level the face is, from 0 for a wall to 1 for a slab. */
private fun levelnessOf(a: Vec3f, b: Vec3f, c: Vec3f): Float {
    val nx = (b.y - a.y) * (c.z - a.z) - (b.z - a.z) * (c.y - a.y)
    val ny = (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z)
    val nz = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    val len = sqrt(nx * nx + ny * ny + nz * nz)
    return if (len < 1e-9f) 0f else ny / len
}

private fun faceKey(a: Int, b: Int, c: Int): Long {
    var lo = a
    var mid = b
    var hi = c
    if (lo > mid) { val s = lo; lo = mid; mid = s }
    if (mid > hi) { val s = mid; mid = hi; hi = s }
    if (lo > mid) { val s = lo; lo = mid; mid = s }
    return (lo.toLong() shl (INDEX_BITS * 2)) or (mid.toLong() shl INDEX_BITS) or hi.toLong()
}

private fun edgeKey(a: Int, b: Int, face: Int): Long {
    val lo = if (a < b) a else b
    val hi = if (a < b) b else a
    return (lo.toLong() shl (INDEX_BITS * 2)) or (hi.toLong() shl INDEX_BITS) or face.toLong()
}

/**
 * An edge belongs to the outline when the faces around it do not all lie in one
 * plane, or when there is only one of them and the surface simply ends there.
 */
private fun isOutline(edges: LongArray, from: Int, to: Int, normals: FloatArray): Boolean {
    if (to - from < 2) return true
    val first = (edges[from] and INDEX_MASK).toInt() * 3
    for (k in from + 1 until to) {
        val other = (edges[k] and INDEX_MASK).toInt() * 3
        // Two faces of the same wall can be wound opposite ways, so only the plane
        // they share counts, not the side each of them looks at.
        val dot = normals[first] * normals[other] +
            normals[first + 1] * normals[other + 1] +
            normals[first + 2] * normals[other + 2]
        if (abs(dot) < CREASE_COS) return true
    }
    return false
}

/**
 * Maps every vertex onto the first one sharing its exact position. Corners are
 * written from the same source value each time, so equality is exact; where it is
 * not, the vertex keeps to itself and its edges are kept as a border, drawing one
 * line too many rather than one too few.
 */
private fun weldByPosition(vertices: List<Vec3f>): IntArray {
    val count = vertices.size
    val canonical = IntArray(count) { it }
    val sorted = LongArray(count)
    for (i in 0 until count) {
        val v = vertices[i]
        var hash = v.x.toRawBits()
        hash = hash * 31 + v.y.toRawBits()
        hash = hash * 31 + v.z.toRawBits()
        sorted[i] = ((hash.toLong() and 0xFFFFFFFFL) shl INDEX_BITS) or i.toLong()
    }
    Arrays.sort(sorted)

    var i = 0
    while (i < count) {
        val hash = sorted[i] ushr INDEX_BITS
        var j = i + 1
        while (j < count && (sorted[j] ushr INDEX_BITS) == hash) j++
        if (j - i in 2..MAX_WELD_GROUP) {
            for (a in i until j) {
                val root = (sorted[a] and INDEX_MASK).toInt()
                if (canonical[root] != root) continue
                val first = vertices[root]
                for (b in a + 1 until j) {
                    val other = (sorted[b] and INDEX_MASK).toInt()
                    if (canonical[other] != other) continue
                    val v = vertices[other]
                    if (first.x == v.x && first.y == v.y && first.z == v.z) canonical[other] = root
                }
            }
        }
        i = j
    }
    return canonical
}
