package com.arcoregeo.campoar.data

import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * STEP/IFC reader that extracts local 3D meshes for any common solid representation
 * and, when present, georeferences footprints via IfcMapConversion / IfcSite angles.
 *
 * Supported geometry (best-effort):
 * - IfcExtrudedAreaSolid with Polyline / IndexedPolyCurve / Rectangle / Circle profiles
 * - IfcTriangulatedFaceSet
 * - IfcPolygonalFaceSet (outer loops only)
 * - IfcFace / IfcPolyLoop (via FacetedBrep-style faces)
 */
object IfcGeoParser {

    private const val EARTH_RADIUS_M = 6_378_137.0

    private data class Entity(val type: String, val args: List<String>)

    private data class MapConversion(
        val eastings: Double,
        val northings: Double,
        val rotationRad: Double,
        val scale: Double,
        val zone: Int,
        val southern: Boolean,
    )

    private data class LocalPoint2(val x: Double, val y: Double)
    private data class LocalPoint3(val x: Double, val y: Double, val z: Double)

    fun parse(fileName: String, text: String): KmzDocument {
        val entities = readEntities(text)
        val meshes = mutableListOf<LocalMesh>()
        val footprints = mutableListOf<Pair<String, List<LocalPoint2>>>()
        var maxHeight = 0f

        entities.values.filter { it.type == "IFCEXTRUDEDAREASOLID" }.forEachIndexed { index, solid ->
            val height = solid.args.getOrNull(3)?.toFloatOrNull() ?: 3f
            maxHeight = maxOf(maxHeight, height)
            val profile = solid.args.getOrNull(0)?.let { entities[refId(it)] } ?: return@forEachIndexed
            val ring2 = readProfileRing(profile, entities) ?: return@forEachIndexed
            if (ring2.size < 3) return@forEachIndexed
            val name = "Extrusión ${index + 1}"
            meshes += extrudeRingToMesh(name, ring2, height)
            footprints += name to ring2
        }

        entities.values.filter { it.type == "IFCTRIANGULATEDFACESET" }.forEachIndexed { index, faceSet ->
            triangulatedFaceSetToMesh(entities, faceSet, "Malla ${index + 1}")?.let { meshes += it }
        }

        entities.values.filter { it.type == "IFCPOLYGONALFACESET" }.forEachIndexed { index, faceSet ->
            polygonalFaceSetToMesh(entities, faceSet, "Caras ${index + 1}")?.let { meshes += it }
        }

        // Faceted Brep poly loops as flat/3D faces.
        entities.values.filter { it.type == "IFCPOLYLOOP" }.forEachIndexed { index, loop ->
            polyLoopToMesh(entities, loop, "Bucle ${index + 1}")?.let { meshes += it }
        }

        if (meshes.isEmpty()) {
            error(
                "No se pudo extraer geometría 3D del IFC. " +
                    "Soporta: ExtrudedAreaSolid (Polyline/IndexedPolyCurve/Rectángulo/Círculo), " +
                    "TriangulatedFaceSet, PolygonalFaceSet y PolyLoop.",
            )
        }

        val mapConversion = readMapConversion(entities)
        val site = entities.values.firstOrNull { it.type == "IFCSITE" }
        val siteOrigin = site?.let {
            val latitude = compoundAngle(it.args.getOrNull(9))
            val longitude = compoundAngle(it.args.getOrNull(10))
            if (latitude != null && longitude != null) LatLngAlt(latitude, longitude) else null
        }
        val georeferenced = mapConversion != null || siteOrigin != null

        val displayName = entities.values.firstOrNull {
            it.type in setOf(
                "IFCBUILDINGELEMENTPROXY",
                "IFCWALL",
                "IFCSLAB",
                "IFCBUILDING",
                "IFCPROJECT",
            )
        }?.args?.getOrNull(2)?.stepText()
            ?: fileName.substringBeforeLast('.').substringAfterLast('/').substringAfterLast(':')

        val polygons = if (georeferenced && footprints.isNotEmpty()) {
            footprints.mapIndexed { i, (name, ring2) ->
                val ring = ring2.map { p ->
                    when {
                        mapConversion != null -> mapConversion.toLatLng(p.x, p.y)
                        else -> fromEnu(siteOrigin!!, p.x, p.y)
                    }
                }
                GeoPolygon(
                    id = UUID.randomUUID().toString(),
                    name = if (footprints.size == 1) displayName else "$displayName · $name",
                    ring = ring,
                )
            }
        } else {
            emptyList()
        }

        return KmzDocument(
            id = UUID.randomUUID().toString(),
            fileName = fileName.substringAfterLast('/').substringAfterLast(':'),
            storedFileName = "",
            importedAtEpochMs = System.currentTimeMillis(),
            points = emptyList(),
            lines = emptyList(),
            polygons = polygons,
            solidHeightMeters = maxHeight.takeIf { it > 0f },
            sourceKind = "ifc",
            isGeoreferenced = georeferenced && polygons.isNotEmpty(),
            localMeshes = meshes,
        )
    }

    // --- Profile / curve readers -------------------------------------------------

    private fun readProfileRing(profile: Entity, entities: Map<Int, Entity>): List<LocalPoint2>? {
        return when (profile.type) {
            "IFCARBITRARYCLOSEDPROFILEDEF",
            "IFCARBITRARYPROFILEDEFWITHVOIDS",
            -> {
                val curve = profile.args.getOrNull(2)?.let { entities[refId(it)] }
                    ?: profile.args.lastOrNull()?.let { entities[refId(it)] }
                curve?.let { readCurve2d(it, entities) }
            }
            "IFCRECTANGLEPROFILEDEF" -> rectangleProfile(profile, entities)
            "IFCCIRCLEPROFILEDEF" -> circleProfile(profile, entities)
            else -> {
                // Some exporters put the curve id as last arg regardless of type.
                profile.args.lastOrNull()?.let { entities[refId(it)] }?.let { readCurve2d(it, entities) }
            }
        }
    }

    private fun readCurve2d(curve: Entity, entities: Map<Int, Entity>): List<LocalPoint2>? {
        return when (curve.type) {
            "IFCPOLYLINE" -> polyline2d(curve, entities)
            "IFCINDEXEDPOLYCURVE" -> indexedPolyCurve2d(curve, entities)
            "IFCCOMPOSITECURVE" -> compositeCurve2d(curve, entities)
            else -> null
        }
    }

    private fun polyline2d(curve: Entity, entities: Map<Int, Entity>): List<LocalPoint2>? {
        val refs = splitArgs(curve.args.first().trim('(', ')'))
        val pts = refs.mapNotNull { ref -> cartesian2(entities[refId(ref)] ?: return@mapNotNull null) }
        return openLocalRing(pts)
    }

    private fun indexedPolyCurve2d(curve: Entity, entities: Map<Int, Entity>): List<LocalPoint2>? {
        val pointsList = curve.args.getOrNull(0)?.let { entities[refId(it)] } ?: return null
        val allPts = cartesianPointList2d(pointsList) ?: return null
        if (allPts.isEmpty()) return null

        val segmentsArg = curve.args.getOrNull(1)?.trim().orEmpty()
        if (segmentsArg.isEmpty() || segmentsArg == "\$") {
            return openLocalRing(allPts)
        }

        // IFCLINEINDEX((1,2,3,1)) or list of those — collect 1-based indices.
        val indices = Regex("\\d+").findAll(segmentsArg).mapNotNull { it.value.toIntOrNull() }.toList()
        if (indices.isEmpty()) return openLocalRing(allPts)
        val pts = indices.mapNotNull { idx -> allPts.getOrNull(idx - 1) }
        return openLocalRing(pts)
    }

    private fun compositeCurve2d(curve: Entity, entities: Map<Int, Entity>): List<LocalPoint2>? {
        val segmentsRaw = curve.args.firstOrNull()?.trim('(', ')') ?: return null
        val pts = mutableListOf<LocalPoint2>()
        splitArgs(segmentsRaw).forEach { segRef ->
            val segment = entities[refId(segRef)] ?: return@forEach
            // IfcCompositeCurveSegment(Transition, SameSense, ParentCurve)
            val parent = segment.args.getOrNull(2)?.let { entities[refId(it)] } ?: return@forEach
            readCurve2d(parent, entities)?.let { part ->
                if (pts.isEmpty()) pts += part
                else pts += part.drop(1)
            }
        }
        return openLocalRing(pts)
    }

    private fun rectangleProfile(profile: Entity, entities: Map<Int, Entity>): List<LocalPoint2>? {
        // IFCRECTANGLEPROFILEDEF(Position, XDim, YDim) — args vary; dims are usually last floats.
        val floats = profile.args.mapNotNull { it.toDoubleOrNull() }
        if (floats.size < 2) return null
        val xDim = floats[floats.size - 2]
        val yDim = floats[floats.size - 1]
        val hx = xDim / 2.0
        val hy = yDim / 2.0
        var ox = 0.0
        var oy = 0.0
        // Optional placement: IfcAxis2Placement2D -> IfcCartesianPoint
        profile.args.firstOrNull { it.startsWith("#") }?.let { ref ->
            val placement = entities[refId(ref)]
            val point = placement?.args?.firstOrNull()?.let { entities[refId(it)] }
            cartesian2(point)?.let {
                ox = it.x
                oy = it.y
            }
        }
        return listOf(
            LocalPoint2(ox - hx, oy - hy),
            LocalPoint2(ox + hx, oy - hy),
            LocalPoint2(ox + hx, oy + hy),
            LocalPoint2(ox - hx, oy + hy),
        )
    }

    private fun circleProfile(profile: Entity, entities: Map<Int, Entity>): List<LocalPoint2>? {
        val radius = profile.args.mapNotNull { it.toDoubleOrNull() }.lastOrNull() ?: return null
        var ox = 0.0
        var oy = 0.0
        profile.args.firstOrNull { it.startsWith("#") }?.let { ref ->
            val placement = entities[refId(ref)]
            val point = placement?.args?.firstOrNull()?.let { entities[refId(it)] }
            cartesian2(point)?.let {
                ox = it.x
                oy = it.y
            }
        }
        val steps = 32
        return (0 until steps).map { i ->
            val a = 2.0 * PI * i / steps
            LocalPoint2(ox + radius * cos(a), oy + radius * sin(a))
        }
    }

    private fun cartesianPointList2d(entity: Entity): List<LocalPoint2>? {
        if (entity.type != "IFCCARTESIANPOINTLIST2D" && entity.type != "IFCCARTESIANPOINTLIST3D") return null
        val body = entity.args.firstOrNull()?.trim() ?: return null
        // ((x,y),(x,y),...) or ((x,y,z),...)
        val pairs = Regex("\\(([^()]+)\\)").findAll(body.removePrefix("(").removeSuffix(")"))
        return pairs.mapNotNull { m ->
            val nums = m.groupValues[1].split(',').mapNotNull { it.trim().toDoubleOrNull() }
            when {
                nums.size >= 2 -> LocalPoint2(nums[0], nums[1])
                else -> null
            }
        }.toList()
    }

    private fun cartesianPointList3d(entity: Entity): List<LocalPoint3>? {
        if (entity.type != "IFCCARTESIANPOINTLIST3D" && entity.type != "IFCCARTESIANPOINTLIST2D") return null
        val body = entity.args.firstOrNull()?.trim() ?: return null
        val pairs = Regex("\\(([^()]+)\\)").findAll(body.removePrefix("(").removeSuffix(")"))
        return pairs.mapNotNull { m ->
            val nums = m.groupValues[1].split(',').mapNotNull { it.trim().toDoubleOrNull() }
            when {
                nums.size >= 3 -> LocalPoint3(nums[0], nums[1], nums[2])
                nums.size == 2 -> LocalPoint3(nums[0], nums[1], 0.0)
                else -> null
            }
        }.toList()
    }

    private fun cartesian2(point: Entity?): LocalPoint2? {
        point ?: return null
        if (point.type != "IFCCARTESIANPOINT") return null
        val coords = splitArgs(point.args.first().trim('(', ')'))
        val x = coords.getOrNull(0)?.toDoubleOrNull() ?: return null
        val y = coords.getOrNull(1)?.toDoubleOrNull() ?: return null
        return LocalPoint2(x, y)
    }

    private fun cartesian3(point: Entity?): LocalPoint3? {
        point ?: return null
        if (point.type != "IFCCARTESIANPOINT") return null
        val coords = splitArgs(point.args.first().trim('(', ')'))
        val x = coords.getOrNull(0)?.toDoubleOrNull() ?: return null
        val y = coords.getOrNull(1)?.toDoubleOrNull() ?: return null
        val z = coords.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        return LocalPoint3(x, y, z)
    }

    private fun openLocalRing(pts: List<LocalPoint2>): List<LocalPoint2> {
        if (pts.size < 2) return pts
        val first = pts.first()
        val last = pts.last()
        return if (abs(first.x - last.x) < 1e-9 && abs(first.y - last.y) < 1e-9) pts.dropLast(1) else pts
    }

    // --- Mesh builders -----------------------------------------------------------

    /** IFC is Z-up; SceneView is Y-up → (x, y, z)_ifc becomes (x, z, -y). */
    private fun toScene(p: LocalPoint3): Vec3f =
        Vec3f(p.x.toFloat(), p.z.toFloat(), (-p.y).toFloat())

    private fun extrudeRingToMesh(name: String, ring: List<LocalPoint2>, height: Float): LocalMesh {
        val base = openLocalRing(ring)
        val vertices = mutableListOf<Vec3f>()
        val indices = mutableListOf<Int>()

        fun addWall(a: LocalPoint2, b: LocalPoint2) {
            val i = vertices.size
            vertices += toScene(LocalPoint3(a.x, a.y, 0.0))
            vertices += toScene(LocalPoint3(b.x, b.y, 0.0))
            vertices += toScene(LocalPoint3(b.x, b.y, height.toDouble()))
            vertices += toScene(LocalPoint3(a.x, a.y, height.toDouble()))
            indices += listOf(i, i + 1, i + 2, i, i + 2, i + 3)
            indices += listOf(i, i + 2, i + 1, i, i + 3, i + 2)
        }

        for (i in base.indices) {
            addWall(base[i], base[(i + 1) % base.size])
        }

        fun addCap(z: Double) {
            val cx = base.map { it.x }.average()
            val cy = base.map { it.y }.average()
            val center = vertices.size
            vertices += toScene(LocalPoint3(cx, cy, z))
            val rim = vertices.size
            base.forEach { vertices += toScene(LocalPoint3(it.x, it.y, z)) }
            for (i in base.indices) {
                val i0 = rim + i
                val i1 = rim + (i + 1) % base.size
                indices += listOf(center, i0, i1)
                indices += listOf(center, i1, i0)
            }
        }
        addCap(height.toDouble())
        addCap(0.0)

        return LocalMesh(name, vertices, indices)
    }

    private fun triangulatedFaceSetToMesh(
        entities: Map<Int, Entity>,
        faceSet: Entity,
        name: String,
    ): LocalMesh? {
        val pointsEntity = faceSet.args.getOrNull(0)?.let { entities[refId(it)] } ?: return null
        val points = cartesianPointList3d(pointsEntity) ?: return null
        // CoordIndex is usually the 4th argument (1-based): ((1,2,3),(…))
        val coordIndexArg = faceSet.args.getOrNull(3) ?: faceSet.args.lastOrNull() ?: return null
        val triangles = mutableListOf<Int>()
        Regex("\\(([^()]+)\\)").findAll(coordIndexArg).forEach { m ->
            val idx = m.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }
            if (idx.size >= 3) {
                triangles += listOf(idx[0] - 1, idx[1] - 1, idx[2] - 1)
            }
        }
        if (triangles.isEmpty()) return null
        return LocalMesh(name, points.map { toScene(it) }, triangles)
    }

    private fun polygonalFaceSetToMesh(
        entities: Map<Int, Entity>,
        faceSet: Entity,
        name: String,
    ): LocalMesh? {
        val pointsEntity = faceSet.args.getOrNull(0)?.let { entities[refId(it)] } ?: return null
        val points = cartesianPointList3d(pointsEntity) ?: return null
        val facesArg = faceSet.args.getOrNull(2) ?: return null
        val vertices = points.map { toScene(it) }
        val indices = mutableListOf<Int>()
        // Faces like ((1,2,3,4),$) or (#12) referencing IfcIndexedPolygonalFace
        Regex("IFCINDEXEDPOLYGONALFACE\\(\\(([^)]+)\\)").findAll(facesArg).forEach { m ->
            fanIndices(m.groupValues[1], indices)
        }
        // Also inline ((1,2,3,4),…)
        if (indices.isEmpty()) {
            Regex("\\((\\d+[\\d,\\s]*)\\)").findAll(facesArg).forEach { m ->
                fanIndices(m.groupValues[1], indices)
            }
        }
        // Resolve face entity refs
        splitArgs(facesArg.trim('(', ')')).forEach { token ->
            val face = entities[refId(token)] ?: return@forEach
            if (face.type.startsWith("IFCINDEXEDPOLYGONALFACE")) {
                face.args.firstOrNull()?.let { fanIndices(it.trim('(', ')'), indices) }
            }
        }
        if (indices.isEmpty()) return null
        return LocalMesh(name, vertices, indices)
    }

    private fun fanIndices(csv: String, out: MutableList<Int>) {
        val idx = csv.split(',').mapNotNull { it.trim().toIntOrNull() }.map { it - 1 }
        if (idx.size < 3) return
        for (i in 1 until idx.size - 1) {
            out += listOf(idx[0], idx[i], idx[i + 1])
        }
    }

    private fun polyLoopToMesh(
        entities: Map<Int, Entity>,
        loop: Entity,
        name: String,
    ): LocalMesh? {
        val refs = splitArgs(loop.args.first().trim('(', ')'))
        val pts = refs.mapNotNull { cartesian3(entities[refId(it)]) }
        if (pts.size < 3) return null
        val vertices = pts.map { toScene(it) }
        val indices = mutableListOf<Int>()
        for (i in 1 until pts.size - 1) {
            indices += listOf(0, i, i + 1)
            indices += listOf(0, i + 1, i)
        }
        return LocalMesh(name, vertices, indices)
    }

    // --- STEP helpers / georef (unchanged logic) --------------------------------

    private fun readEntities(text: String): Map<Int, Entity> {
        val result = mutableMapOf<Int, Entity>()
        splitStatements(text.substringAfter("DATA;", text)).forEach { raw ->
            val statement = raw.replace('\n', ' ').replace('\r', ' ').trim()
            if (!statement.startsWith("#")) return@forEach
            val eq = statement.indexOf('=')
            val open = statement.indexOf('(', eq + 1)
            if (eq < 0 || open < 0) return@forEach
            val id = statement.substring(1, eq).trim().toIntOrNull() ?: return@forEach
            val type = statement.substring(eq + 1, open).trim().uppercase()
            val close = statement.lastIndexOf(')')
            if (close <= open) return@forEach
            result[id] = Entity(type, splitArgs(statement.substring(open + 1, close)))
        }
        return result
    }

    private fun splitStatements(body: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var inString = false
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            when {
                inString -> {
                    current.append(ch)
                    if (ch == '\'') {
                        if (i + 1 < body.length && body[i + 1] == '\'') {
                            current.append('\'')
                            i++
                        } else {
                            inString = false
                        }
                    }
                }
                ch == '\'' -> {
                    inString = true
                    current.append(ch)
                }
                ch == ';' -> {
                    statements.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        if (current.isNotBlank()) statements.add(current.toString())
        return statements
    }

    private fun splitArgs(input: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        input.forEach { ch ->
            when {
                inString -> {
                    current.append(ch)
                    if (ch == '\'') inString = false
                }
                ch == '\'' -> {
                    inString = true
                    current.append(ch)
                }
                ch == '(' -> {
                    depth++
                    current.append(ch)
                }
                ch == ')' -> {
                    depth--
                    current.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    parts.add(current.toString().trim())
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString().trim())
        return parts
    }

    private fun readMapConversion(entities: Map<Int, Entity>): MapConversion? {
        val conversion = entities.values.firstOrNull { it.type == "IFCMAPCONVERSION" } ?: return null
        val eastings = conversion.args.getOrNull(2)?.toDoubleOrNull() ?: return null
        val northings = conversion.args.getOrNull(3)?.toDoubleOrNull() ?: return null
        val abscissa = conversion.args.getOrNull(5)?.toDoubleOrNull() ?: 1.0
        val ordinate = conversion.args.getOrNull(6)?.toDoubleOrNull() ?: 0.0
        val scale = conversion.args.getOrNull(7)?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1.0
        val crs = conversion.args.getOrNull(1)?.let { entities[refId(it)] }
            ?: entities.values.firstOrNull { it.type == "IFCPROJECTEDCRS" }
        val (zone, southern) = crs?.let { utmZoneOf(it) } ?: return null
        return MapConversion(
            eastings = eastings,
            northings = northings,
            rotationRad = atan2(ordinate, abscissa),
            scale = scale,
            zone = zone,
            southern = southern,
        )
    }

    private fun utmZoneOf(crs: Entity): Pair<Int, Boolean>? {
        val text = crs.args.joinToString(",")
        Regex("32[67]\\d{2}").find(text)?.let { match ->
            val code = match.value.toInt()
            val zone = code % 100
            if (zone in 1..60) return zone to (code / 100 == 327)
        }
        Regex("(?i)zone\\s*'?\\s*(\\d{1,2})\\s*([NS])").find(text)?.let { match ->
            return match.groupValues[1].toInt() to match.groupValues[2].equals("S", true)
        }
        Regex("'(\\d{1,2})([NSns])'").find(text)?.let { match ->
            return match.groupValues[1].toInt() to match.groupValues[2].equals("S", true)
        }
        return null
    }

    private fun MapConversion.toLatLng(localX: Double, localY: Double): LatLngAlt {
        val c = cos(rotationRad)
        val s = sin(rotationRad)
        val easting = eastings + (localX * c - localY * s) * scale
        val northing = northings + (localX * s + localY * c) * scale
        return utmToLatLng(easting, northing, zone, southern)
    }

    private fun utmToLatLng(easting: Double, northing: Double, zone: Int, southern: Boolean): LatLngAlt {
        val a = EARTH_RADIUS_M
        val f = 1 / 298.257223563
        val k0 = 0.9996
        val e2 = f * (2 - f)
        val ep2 = e2 / (1 - e2)

        val x = easting - 500_000.0
        val y = if (southern) northing - 10_000_000.0 else northing

        val m = y / k0
        val mu = m / (a * (1 - e2 / 4 - 3 * e2.pow(2) / 64 - 5 * e2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val phi = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)

        val c1 = ep2 * cos(phi).pow(2)
        val t1 = tan(phi).pow(2)
        val sinPhi2 = sin(phi).pow(2)
        val n1 = a / sqrt(1 - e2 * sinPhi2)
        val r1 = a * (1 - e2) / (1 - e2 * sinPhi2).pow(1.5)
        val d = x / (n1 * k0)

        val latitude = phi - (n1 * tan(phi) / r1) * (
            d.pow(2) / 2 -
                (5 + 3 * t1 + 10 * c1 - 4 * c1.pow(2) - 9 * ep2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1.pow(2) - 252 * ep2 - 3 * c1.pow(2)) * d.pow(6) / 720
            )
        val lon0 = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)
        val longitude = lon0 + (
            d -
                (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1.pow(2) + 8 * ep2 + 24 * t1.pow(2)) * d.pow(5) / 120
            ) / cos(phi)

        return LatLngAlt(Math.toDegrees(latitude), Math.toDegrees(longitude))
    }

    private fun refId(token: String): Int? = token.trim().removePrefix("#").toIntOrNull()

    private fun String.stepText(): String? =
        trim().takeIf { it.startsWith("'") }?.trim('\'')?.takeIf { it.isNotBlank() }

    private fun compoundAngle(token: String?): Double? {
        val raw = token?.trim() ?: return null
        if (!raw.startsWith("(")) return null
        val parts = splitArgs(raw.trim('(', ')')).mapNotNull { it.toDoubleOrNull() }
        if (parts.isEmpty()) return null
        val sign = if (parts.any { it < 0 }) -1.0 else 1.0
        return sign * (
            abs(parts.getOrElse(0) { 0.0 }) +
                abs(parts.getOrElse(1) { 0.0 }) / 60.0 +
                abs(parts.getOrElse(2) { 0.0 }) / 3600.0 +
                abs(parts.getOrElse(3) { 0.0 }) / 3_600_000_000.0
            )
    }

    private fun fromEnu(origin: LatLngAlt, east: Double, north: Double): LatLngAlt {
        val latitude = origin.latitude + Math.toDegrees(north / EARTH_RADIUS_M)
        val longitude = origin.longitude +
            Math.toDegrees(east / (EARTH_RADIUS_M * cos(Math.toRadians(origin.latitude))))
        return LatLngAlt(latitude, longitude)
    }
}
