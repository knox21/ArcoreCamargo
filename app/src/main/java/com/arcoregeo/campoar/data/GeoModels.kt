package com.arcoregeo.campoar.data

import kotlinx.serialization.Serializable

@Serializable
data class LatLngAlt(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
)

@Serializable
data class GeoPoint(
    val id: String,
    val name: String,
    val description: String = "",
    val coordinate: LatLngAlt,
)

@Serializable
data class GeoLine(
    val id: String,
    val name: String,
    val coordinates: List<LatLngAlt>,
)

@Serializable
data class GeoPolygon(
    val id: String,
    val name: String,
    val ring: List<LatLngAlt>,
)

/** Local XYZ mesh in metres (Y-up, for SceneView / AR). */
@Serializable
data class Vec3f(
    val x: Float,
    val y: Float,
    val z: Float,
)

@Serializable
data class LocalMesh(
    val name: String,
    val vertices: List<Vec3f>,
    val indices: List<Int>,
)

@Serializable
data class KmzDocument(
    val id: String,
    val fileName: String,
    val storedFileName: String,
    val importedAtEpochMs: Long,
    val points: List<GeoPoint>,
    val lines: List<GeoLine> = emptyList(),
    val polygons: List<GeoPolygon> = emptyList(),
    /** Extrusion height when the source was an IFC solid. */
    val solidHeightMeters: Float? = null,
    /** kml | kmz | ifc */
    val sourceKind: String = "kml",
    val isGeoreferenced: Boolean = false,
    /** Local 3D meshes for the IFC viewer (and non-geo solids). */
    val localMeshes: List<LocalMesh> = emptyList(),
    /** Where the mesh local origin sits on Earth, so AR can place the real model. */
    val meshOrigin: LatLngAlt? = null,
    /** Rotation from the mesh local axes to true north, degrees counter-clockwise. */
    val meshRotationDeg: Float = 0f,
    /** What the IFC reader extracted, dropped or could not read. */
    val geometryNote: String? = null,
) {
    val featureCount: Int get() = points.size + lines.size + polygons.size + localMeshes.size

    val isIfc: Boolean get() = sourceKind.equals("ifc", ignoreCase = true) ||
        fileName.endsWith(".ifc", ignoreCase = true) ||
        localMeshes.isNotEmpty()

    /** Vertices for navigation chips / AR. Does not include the geometric centroid. */
    fun arTargets(): List<GeoPoint> {
        if (points.isNotEmpty()) return points
        val fromPolygons = polygons.flatMap { polygon ->
            val ring = openRing(polygon.ring)
            ring.mapIndexed { index, coord ->
                GeoPoint(
                    id = "${polygon.id}-v$index",
                    name = "${polygon.name} · v${index + 1}",
                    description = "Vértice del polígono",
                    coordinate = coord,
                )
            }
        }
        if (fromPolygons.isNotEmpty()) return fromPolygons
        return lines.flatMap { line ->
            line.coordinates.mapIndexed { index, coord ->
                GeoPoint(
                    id = "${line.id}-v$index",
                    name = "${line.name} · v${index + 1}",
                    description = "Vértice de la línea",
                    coordinate = coord,
                )
            }
        }
    }

    fun boundsOrNull(): Pair<LatLngAlt, LatLngAlt>? {
        val coords = buildList {
            points.forEach { add(it.coordinate) }
            lines.forEach { addAll(it.coordinates) }
            polygons.forEach { addAll(it.ring) }
        }
        if (coords.isEmpty()) return null
        return LatLngAlt(
            latitude = coords.minOf { it.latitude },
            longitude = coords.minOf { it.longitude },
        ) to LatLngAlt(
            latitude = coords.maxOf { it.latitude },
            longitude = coords.maxOf { it.longitude },
        )
    }
}

@Serializable
data class LibraryIndex(
    val documents: List<KmzDocument> = emptyList(),
)

fun openRing(ring: List<LatLngAlt>): List<LatLngAlt> {
    if (ring.size < 2) return ring
    val first = ring.first()
    val last = ring.last()
    return if (
        first.latitude == last.latitude &&
        first.longitude == last.longitude
    ) {
        ring.dropLast(1)
    } else {
        ring
    }
}

fun centroidOf(coords: List<LatLngAlt>): LatLngAlt? {
    if (coords.isEmpty()) return null
    return LatLngAlt(
        latitude = coords.map { it.latitude }.average(),
        longitude = coords.map { it.longitude }.average(),
        altitude = coords.mapNotNull { it.altitude }.average().takeIf { !it.isNaN() },
    )
}
