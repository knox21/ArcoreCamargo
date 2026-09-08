package com.arcoregeo.campoar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Solids leave the IFC parser with every triangle mirrored back to front and with a
 * private copy of each corner per face. Both habits break the naive reading of a
 * mesh: normals cancel out and no edge is ever shared.
 */
class MeshDisplayTest {

    private val size = 2f
    private val height = 3f
    private val centre = Triple(size / 2f, height / 2f, size / 2f)

    /** A box written exactly the way IfcGeoParser writes an extruded solid. */
    private fun prism(clockwise: Boolean = false): LocalMesh {
        val ring = listOf(0f to 0f, size to 0f, size to size, 0f to size)
        val order = if (clockwise) ring.reversed() else ring
        val vertices = mutableListOf<Vec3f>()
        val indices = mutableListOf<Int>()

        for (i in order.indices) {
            val a = order[i]
            val b = order[(i + 1) % order.size]
            val at = vertices.size
            vertices += Vec3f(a.first, 0f, a.second)
            vertices += Vec3f(b.first, 0f, b.second)
            vertices += Vec3f(b.first, height, b.second)
            vertices += Vec3f(a.first, height, a.second)
            indices += listOf(at, at + 1, at + 2, at, at + 2, at + 3)
            indices += listOf(at, at + 2, at + 1, at, at + 3, at + 2)
        }
        listOf(height, 0f).forEach { y ->
            val middle = vertices.size
            vertices += Vec3f(size / 2f, y, size / 2f)
            val rim = vertices.size
            order.forEach { vertices += Vec3f(it.first, y, it.second) }
            for (i in order.indices) {
                val a = rim + i
                val b = rim + (i + 1) % order.size
                indices += listOf(middle, a, b)
                indices += listOf(middle, b, a)
            }
        }
        return LocalMesh("prisma", vertices, indices)
    }

    private fun segments(mesh: LocalMesh, edges: List<Int>): Set<Set<Triple<Float, Float, Float>>> =
        edges.chunked(2).map { (a, b) ->
            val va = mesh.vertices[a]
            val vb = mesh.vertices[b]
            setOf(Triple(va.x, va.y, va.z), Triple(vb.x, vb.y, vb.z))
        }.toSet()

    private fun boxEdges(): Set<Set<Triple<Float, Float, Float>>> {
        val ring = listOf(0f to 0f, size to 0f, size to size, 0f to size)
        val out = mutableSetOf<Set<Triple<Float, Float, Float>>>()
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            listOf(0f, height).forEach { y ->
                out += setOf(Triple(a.first, y, a.second), Triple(b.first, y, b.second))
            }
            out += setOf(Triple(a.first, 0f, a.second), Triple(a.first, height, a.second))
        }
        return out
    }

    @Test
    fun `outlines a box with its twelve edges and no face diagonals`() {
        val mesh = prism()

        val edges = featureEdges(mesh)

        assertEquals(12, edges.size / 2)
        assertEquals(boxEdges(), segments(mesh, edges))
    }

    @Test
    fun `walls of a solid face away from it instead of all pointing up`() {
        val mesh = prism()

        val normals = shadingOf(mesh).normals

        var wallCount = 0
        mesh.vertices.forEachIndexed { i, v ->
            val nx = normals[i * 3]
            val ny = normals[i * 3 + 1]
            val nz = normals[i * 3 + 2]
            if (abs(ny) > 0.5f) return@forEachIndexed
            wallCount++
            val outward = nx * (v.x - centre.first) + nz * (v.z - centre.third)
            assertTrue("wall normal at $v points inwards: $nx, $ny, $nz", outward > 0f)
        }
        assertTrue("no wall normal survived averaging", wallCount >= 16)
    }

    @Test
    fun `the roof faces up whichever way the footprint was drawn`() {
        listOf(false, true).forEach { clockwise ->
            val mesh = prism(clockwise)
            val normals = shadingOf(mesh).normals
            // The middle of the roof fan belongs to no wall, so its normal is the
            // roof's own and nothing else averages into it.
            val roof = mesh.vertices.indexOfFirst {
                it.y == height && it.x == size / 2f && it.z == size / 2f
            }

            assertTrue(
                "roof points down when the footprint is drawn clockwise=$clockwise",
                normals[roof * 3 + 1] > 0.9f,
            )
        }
    }

    @Test
    fun `faces are grouped into walls and slabs with both copies together`() {
        val mesh = prism()

        val shading = shadingOf(mesh)

        // Four wall quads and two square caps, each triangle written twice.
        assertEquals(16 * 3, shading.walls.size)
        assertEquals(8 * 3, shading.tops.size)
        assertEquals(8 * 3, shading.bottoms.size)
        assertEquals(mesh.indices.size, shading.walls.size + shading.tops.size + shading.bottoms.size)
    }

    @Test
    fun `an index past the end of the vertices is dropped rather than drawn`() {
        val mesh = prism()
        val broken = LocalMesh(
            name = mesh.name,
            vertices = mesh.vertices,
            indices = mesh.indices + listOf(0, 1, mesh.vertices.size + 7),
        )

        val shading = shadingOf(broken)

        assertEquals(mesh.indices.size, shading.walls.size + shading.tops.size + shading.bottoms.size)
        assertEquals(featureEdges(mesh).size, featureEdges(broken).size)
    }

    @Test
    fun `a solid with no usable triangles asks for nothing to be drawn`() {
        val flat = LocalMesh(
            name = "degenerado",
            vertices = listOf(Vec3f(0f, 0f, 0f), Vec3f(1f, 0f, 0f), Vec3f(2f, 0f, 0f)),
            indices = listOf(0, 1, 2),
        )

        val shading = shadingOf(flat)

        assertTrue(shading.walls.isEmpty() && shading.tops.isEmpty() && shading.bottoms.isEmpty())
        assertTrue(featureEdges(flat).isEmpty())
    }

    @Test
    fun `the outline is worked out once and then handed back`() {
        val meshes = listOf(prism(), prism(clockwise = true))
        val cache = MeshEdgeCache(meshes)

        val first = cache.edges()
        val second = cache.edges()

        assertTrue(first === second)
        assertEquals(2, first.size)
        assertEquals(12, first[0].size / 2)
    }
}
