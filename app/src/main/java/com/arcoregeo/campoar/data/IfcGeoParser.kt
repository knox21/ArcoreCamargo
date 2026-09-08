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
 * STEP/IFC reader for Revit / IFC2X3 / IFC4 buildings and simple solids.
 *
 * Walks products (walls, slabs, columns, …) with ObjectPlacement transforms,
 * Body representations, extruded profiles, boolean clipping and mapped items.
 *
 * Large Revit exports are mostly properties, quantities and relationships, so
 * those entities are dropped while reading and points are stored as raw
 * coordinates. Geometry output is capped to keep the renderer within budget.
 */
object IfcGeoParser {

    private const val EARTH_RADIUS_M = 6_378_137.0
    private const val MAX_PRODUCTS = 6_000
    private const val MAX_TOTAL_VERTICES = 150_000
    private const val MAX_CHUNK_VERTICES = 30_000
    private const val MAX_FOOTPRINTS = 60

    private class Entity(val type: String, val argsRaw: String) {
        private var parsed: List<String>? = null
        val args: List<String>
            get() = parsed ?: splitArgs(argsRaw).also { parsed = it }
    }

    /** Points and directions are the bulk of an IFC; keep them as plain numbers. */
    private class Model {
        val entities = HashMap<Int, Entity>()
        val coords = HashMap<Int, DoubleArray>()

        fun entity(id: Int?): Entity? = id?.let { entities[it] }
        fun coord(id: Int?): DoubleArray? = id?.let { coords[it] }
    }

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

    /** Row-major 4×4 affine transform. */
    private data class Mat4(
        val m00: Double, val m01: Double, val m02: Double, val m03: Double,
        val m10: Double, val m11: Double, val m12: Double, val m13: Double,
        val m20: Double, val m21: Double, val m22: Double, val m23: Double,
    ) {
        fun mul(o: Mat4): Mat4 = Mat4(
            m00 * o.m00 + m01 * o.m10 + m02 * o.m20,
            m00 * o.m01 + m01 * o.m11 + m02 * o.m21,
            m00 * o.m02 + m01 * o.m12 + m02 * o.m22,
            m00 * o.m03 + m01 * o.m13 + m02 * o.m23 + m03,
            m10 * o.m00 + m11 * o.m10 + m12 * o.m20,
            m10 * o.m01 + m11 * o.m11 + m12 * o.m21,
            m10 * o.m02 + m11 * o.m12 + m12 * o.m22,
            m10 * o.m03 + m11 * o.m13 + m12 * o.m23 + m13,
            m20 * o.m00 + m21 * o.m10 + m22 * o.m20,
            m20 * o.m01 + m21 * o.m11 + m22 * o.m21,
            m20 * o.m02 + m21 * o.m12 + m22 * o.m22,
            m20 * o.m03 + m21 * o.m13 + m22 * o.m23 + m23,
        )

        fun transform(p: LocalPoint3): LocalPoint3 = LocalPoint3(
            m00 * p.x + m01 * p.y + m02 * p.z + m03,
            m10 * p.x + m11 * p.y + m12 * p.z + m13,
            m20 * p.x + m21 * p.y + m22 * p.z + m23,
        )

        fun transformDir(p: LocalPoint3): LocalPoint3 = LocalPoint3(
            m00 * p.x + m01 * p.y + m02 * p.z,
            m10 * p.x + m11 * p.y + m12 * p.z,
            m20 * p.x + m21 * p.y + m22 * p.z,
        )

        companion object {
            val IDENTITY = Mat4(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)

            fun translation(x: Double, y: Double, z: Double) =
                Mat4(1.0, 0.0, 0.0, x, 0.0, 1.0, 0.0, y, 0.0, 0.0, 1.0, z)

            fun fromAxes(origin: LocalPoint3, axisZ: LocalPoint3, axisX: LocalPoint3): Mat4 {
                val z = normalize(axisZ)
                var x = normalize(axisX)
                val dot = x.x * z.x + x.y * z.y + x.z * z.z
                x = normalize(LocalPoint3(x.x - z.x * dot, x.y - z.y * dot, x.z - z.z * dot))
                if (length(x) < 1e-9) {
                    x = if (abs(z.x) < 0.9) LocalPoint3(1.0, 0.0, 0.0) else LocalPoint3(0.0, 1.0, 0.0)
                    val d = x.x * z.x + x.y * z.y + x.z * z.z
                    x = normalize(LocalPoint3(x.x - z.x * d, x.y - z.y * d, x.z - z.z * d))
                }
                val y = cross(z, x)
                return Mat4(
                    x.x, y.x, z.x, origin.x,
                    x.y, y.y, z.y, origin.y,
                    x.z, y.z, z.z, origin.z,
                )
            }
        }
    }

    private val PRODUCT_TYPES = setOf(
        "IFCWALL", "IFCWALLSTANDARDCASE", "IFCSLAB", "IFCBEAM", "IFCBEAMSTANDARDCASE",
        "IFCCOLUMN", "IFCCOLUMNSTANDARDCASE", "IFCMEMBER", "IFCMEMBERSTANDARDCASE",
        "IFCPLATE", "IFCPLATESTANDARDCASE", "IFCFOOTING", "IFCPILE", "IFCCOVERING",
        "IFCCURTAINWALL", "IFCRAILING", "IFCRAMP", "IFCRAMPFLIGHT", "IFCSTAIR",
        "IFCSTAIRFLIGHT", "IFCBUILDINGELEMENTPROXY", "IFCROOF", "IFCCHIMNEY",
        "IFCSHADINGDEVICE", "IFCFURNISHINGELEMENT", "IFCFLOWTERMINAL",
        "IFCFLOWSEGMENT", "IFCFLOWFITTING", "IFCDISCRETEACCESSORY",
        "IFCELEMENTASSEMBLY", "IFCREINFORCINGBAR", "IFCREINFORCINGMESH",
        "IFCDOOR", "IFCWINDOW",
    )

    /** Bookkeeping / metadata entities that never contribute geometry. */
    private val SKIPPED_PREFIXES = listOf(
        "IFCPROPERTY", "IFCCOMPLEXPROPERTY", "IFCREL", "IFCQUANTITY", "IFCELEMENTQUANTITY",
        "IFCOWNERHISTORY", "IFCPERSON", "IFCORGANIZATION", "IFCAPPLICATION",
        "IFCSIUNIT", "IFCDERIVEDUNIT", "IFCUNITASSIGNMENT", "IFCMEASUREWITHUNIT",
        "IFCCONVERSIONBASEDUNIT", "IFCDIMENSIONALEXPONENTS", "IFCMONETARYUNIT",
        "IFCPOSTALADDRESS", "IFCTELECOMADDRESS", "IFCPRESENTATIONSTYLE",
        "IFCPRESENTATIONLAYER", "IFCSURFACESTYLE", "IFCCURVESTYLE", "IFCTEXTSTYLE",
        "IFCFILLAREASTYLE", "IFCCOLOURRGB", "IFCMATERIAL", "IFCCLASSIFICATION",
        "IFCDOCUMENT", "IFCTASK", "IFCACTOR", "IFCCONSTRAINT", "IFCOBJECTIVE",
    )

    fun parse(fileName: String, text: String): KmzDocument {
        val model = readModel(text)
        val meshes = mutableListOf<LocalMesh>()
        val footprints = mutableListOf<Pair<String, List<LocalPoint2>>>()
        var maxHeight = 0f
        var vertexBudget = MAX_TOTAL_VERTICES

        fun addMesh(mesh: LocalMesh, ring: List<LocalPoint2>?, height: Float?) {
            if (vertexBudget <= 0) return
            if (mesh.vertices.size > vertexBudget) return
            vertexBudget -= mesh.vertices.size
            meshes += mesh
            if (ring != null && ring.size >= 3 && footprints.size < MAX_FOOTPRINTS) {
                footprints += mesh.name to ring
            }
            if (height != null) maxHeight = maxOf(maxHeight, height)
        }

        val products = model.entities.entries
            .filter { it.value.type in PRODUCT_TYPES }
            .take(MAX_PRODUCTS)

        if (products.isNotEmpty()) {
            val siteWorld = model.entities.values.firstOrNull { it.type == "IFCSITE" }
                ?.args?.getOrNull(5)?.let { placementWorldTranslation(model, refId(it)) }
                ?: LocalPoint3(0.0, 0.0, 0.0)
            // Cancel huge site engineering coords; keep building-local meters.
            val originCancel = Mat4.translation(-siteWorld.x, -siteWorld.y, -siteWorld.z)

            for ((_, product) in products) {
                if (vertexBudget <= 0) break
                val name = product.args.getOrNull(2)?.stepText()
                    ?: product.args.getOrNull(7)?.stepText()
                    ?: product.type.removePrefix("IFC")
                val productMat = originCancel.mul(localPlacementMatrix(model, refId(product.args.getOrNull(5))))
                val shape = model.entity(refId(product.args.getOrNull(6))) ?: continue
                if (shape.type != "IFCPRODUCTDEFINITIONSHAPE") continue
                val repsArg = shape.args.getOrNull(2) ?: continue
                val reps = splitArgs(repsArg.trim('(', ')'))
                    .mapNotNull { model.entity(refId(it)) }
                    .filter { it.type == "IFCSHAPEREPRESENTATION" }
                    .sortedByDescending { rep ->
                        val id = rep.args.getOrNull(1)?.uppercase().orEmpty()
                        when {
                            id.contains("BODY") -> 3
                            id.contains("BOX") -> 0
                            else -> 1
                        }
                    }
                for (rep in reps) {
                    val identifier = rep.args.getOrNull(1)?.uppercase().orEmpty()
                    if (identifier.contains("AXIS") || identifier.contains("FOOTPRINT") ||
                        identifier.contains("ANNOTATION") || identifier.contains("CLEARANCE") ||
                        identifier.contains("BOX")
                    ) {
                        continue
                    }
                    val itemsArg = rep.args.getOrNull(3) ?: continue
                    var built = false
                    for (itemRef in splitArgs(itemsArg.trim('(', ')'))) {
                        extractSolidMeshes(model, refId(itemRef), productMat, name).forEach { (mesh, ring, height) ->
                            addMesh(mesh, ring, height)
                            built = true
                        }
                    }
                    if (built) break
                }
            }
        }

        if (meshes.isEmpty()) {
            // Simple IFCs without typed products (single extruded solid exports).
            model.entities.values.filter { it.type == "IFCEXTRUDEDAREASOLID" }
                .forEachIndexed { index, solid ->
                    extractExtruded(model, solid, Mat4.IDENTITY, "Extrusión ${index + 1}")
                        .forEach { (mesh, ring, height) -> addMesh(mesh, ring, height) }
                }
            model.entities.values.filter { it.type == "IFCTRIANGULATEDFACESET" }
                .forEachIndexed { i, fs ->
                    triangulatedFaceSetToMesh(model, fs, Mat4.IDENTITY, "Malla ${i + 1}")
                        ?.let { addMesh(it, null, null) }
                }
            model.entities.values.filter { it.type == "IFCPOLYGONALFACESET" }
                .forEachIndexed { i, fs ->
                    polygonalFaceSetToMesh(model, fs, Mat4.IDENTITY, "Caras ${i + 1}")
                        ?.let { addMesh(it, null, null) }
                }
        }

        if (meshes.isEmpty()) {
            error(
                "No se pudo extraer geometría 3D del IFC. " +
                    "Soporta edificios Revit (muros/losas/columnas con ExtrudedAreaSolid), " +
                    "BooleanClipping, MappedItem, TriangulatedFaceSet y PolygonalFaceSet.",
            )
        }

        val merged = mergeMeshes(meshes)

        val mapConversion = readMapConversion(model)
        val site = model.entities.values.firstOrNull { it.type == "IFCSITE" }
        val siteOrigin = site?.let {
            val latitude = compoundAngle(it.args.getOrNull(9))
            val longitude = compoundAngle(it.args.getOrNull(10))
            if (latitude != null && longitude != null) LatLngAlt(latitude, longitude) else null
        }
        val georeferenced = mapConversion != null || siteOrigin != null

        val displayName = model.entities.values.firstOrNull {
            it.type in setOf("IFCBUILDING", "IFCPROJECT", "IFCBUILDINGELEMENTPROXY")
        }?.args?.getOrNull(2)?.stepText()
            ?: fileName.substringBeforeLast('.').substringAfterLast('/').substringAfterLast(':')

        val polygons = if (georeferenced && footprints.isNotEmpty()) {
            footprints.map { (name, ring2) ->
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
            fileName = FileNames.sanitize(fileName, "modelo.ifc"),
            storedFileName = "",
            importedAtEpochMs = System.currentTimeMillis(),
            points = emptyList(),
            lines = emptyList(),
            polygons = polygons,
            solidHeightMeters = maxHeight.takeIf { it > 0f },
            sourceKind = "ifc",
            isGeoreferenced = georeferenced && polygons.isNotEmpty(),
            localMeshes = merged,
            meshOrigin = mapConversion?.toLatLng(0.0, 0.0) ?: siteOrigin,
            meshRotationDeg = mapConversion?.let { modelToNorthDegrees(it) } ?: 0f,
        )
    }

    // --- Product geometry --------------------------------------------------------

    private fun extractSolidMeshes(
        model: Model,
        id: Int?,
        transform: Mat4,
        name: String,
    ): List<Triple<LocalMesh, List<LocalPoint2>?, Float?>> {
        val entity = model.entity(id) ?: return emptyList()
        return when (entity.type) {
            "IFCEXTRUDEDAREASOLID" -> extractExtruded(model, entity, transform, name)
            "IFCBOOLEANCLIPPINGRESULT", "IFCBOOLEANRESULT" -> {
                // FirstOperand is the main solid; SecondOperand is the void/clip.
                extractSolidMeshes(model, refId(entity.args.getOrNull(1)), transform, name)
            }
            "IFCMAPPEDITEM" -> {
                val source = model.entity(refId(entity.args.getOrNull(0)))
                val target = model.entity(refId(entity.args.getOrNull(1)))
                val sourceMat = axis2Placement3dMatrix(model, refId(source?.args?.getOrNull(0)))
                val targetMat = cartesianTransformationOperator3d(model, target)
                val mappedRep = model.entity(refId(source?.args?.getOrNull(1)))
                val items = mappedRep?.args?.getOrNull(3) ?: return emptyList()
                val combined = transform.mul(targetMat).mul(sourceMat)
                splitArgs(items.trim('(', ')')).flatMap { item ->
                    extractSolidMeshes(model, refId(item), combined, name)
                }
            }
            "IFCTRIANGULATEDFACESET" -> listOfNotNull(
                triangulatedFaceSetToMesh(model, entity, transform, name)?.let { Triple(it, null, null) },
            )
            "IFCPOLYGONALFACESET" -> listOfNotNull(
                polygonalFaceSetToMesh(model, entity, transform, name)?.let { Triple(it, null, null) },
            )
            "IFCFACETEDBREP", "IFCFACETEDBREPWITHVOIDS" -> {
                val shell = model.entity(refId(entity.args.firstOrNull { it.startsWith("#") }))
                facetedBrepToMesh(model, shell, transform, name)?.let {
                    listOf(Triple(it, null, null))
                } ?: emptyList()
            }
            "IFCSHELLBASEDSURFACEMODEL" -> {
                splitArgs(entity.args.firstOrNull()?.trim('(', ')').orEmpty()).flatMap { shellRef ->
                    facetedBrepToMesh(model, model.entity(refId(shellRef)), transform, name)
                        ?.let { listOf(Triple(it, null as List<LocalPoint2>?, null as Float?)) }
                        ?: emptyList()
                }
            }
            "IFCSTYLEDITEM" -> {
                val item = entity.args.getOrNull(0)
                if (item != null && item != "\$") extractSolidMeshes(model, refId(item), transform, name)
                else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun extractExtruded(
        model: Model,
        solid: Entity,
        productTransform: Mat4,
        name: String,
    ): List<Triple<LocalMesh, List<LocalPoint2>?, Float?>> {
        val profile = model.entity(refId(solid.args.getOrNull(0))) ?: return emptyList()
        val depth = solid.args.getOrNull(3)?.toDoubleOrNull() ?: return emptyList()
        if (depth <= 0.0) return emptyList()

        val ring2 = readProfileRing(profile, model) ?: return emptyList()
        if (ring2.size < 3) return emptyList()

        val solidPos = axis2Placement3dMatrix(model, refId(solid.args.getOrNull(1)))
        val localDir = direction3(model, refId(solid.args.getOrNull(2))) ?: LocalPoint3(0.0, 0.0, 1.0)
        val world = productTransform.mul(solidPos)
        val dir = normalize(world.transformDir(localDir))
        val tip = LocalPoint3(dir.x * depth, dir.y * depth, dir.z * depth)

        val base = ring2.map { p -> world.transform(LocalPoint3(p.x, p.y, 0.0)) }
        val top = base.map { p -> LocalPoint3(p.x + tip.x, p.y + tip.y, p.z + tip.z) }

        val mesh = extrudedPrismMesh(name, base, top)
        val footprint = base.map { LocalPoint2(it.x, it.y) }
        return listOf(Triple(mesh, footprint, depth.toFloat()))
    }

    private fun extrudedPrismMesh(
        name: String,
        base: List<LocalPoint3>,
        top: List<LocalPoint3>,
    ): LocalMesh {
        val vertices = mutableListOf<Vec3f>()
        val indices = mutableListOf<Int>()
        val n = base.size

        fun addWall(i0: Int, i1: Int) {
            val i = vertices.size
            vertices += toScene(base[i0])
            vertices += toScene(base[i1])
            vertices += toScene(top[i1])
            vertices += toScene(top[i0])
            indices += listOf(i, i + 1, i + 2, i, i + 2, i + 3)
            indices += listOf(i, i + 2, i + 1, i, i + 3, i + 2)
        }
        for (i in 0 until n) addWall(i, (i + 1) % n)

        fun addCap(pts: List<LocalPoint3>) {
            val cx = pts.map { it.x }.average()
            val cy = pts.map { it.y }.average()
            val cz = pts.map { it.z }.average()
            val center = vertices.size
            vertices += toScene(LocalPoint3(cx, cy, cz))
            val rim = vertices.size
            pts.forEach { vertices += toScene(it) }
            for (i in pts.indices) {
                val a = rim + i
                val b = rim + (i + 1) % pts.size
                indices += listOf(center, a, b)
                indices += listOf(center, b, a)
            }
        }
        addCap(top)
        addCap(base)
        return LocalMesh(name, vertices, indices)
    }

    // --- Placements --------------------------------------------------------------

    private fun localPlacementMatrix(model: Model, id: Int?, depth: Int = 0): Mat4 {
        if (depth > 64) return Mat4.IDENTITY
        val placement = model.entity(id) ?: return Mat4.IDENTITY
        if (placement.type != "IFCLOCALPLACEMENT") return Mat4.IDENTITY
        val parentRef = placement.args.getOrNull(0)
        val parent = if (parentRef == null || parentRef == "\$") {
            Mat4.IDENTITY
        } else {
            localPlacementMatrix(model, refId(parentRef), depth + 1)
        }
        return parent.mul(axis2Placement3dMatrix(model, refId(placement.args.getOrNull(1))))
    }

    private fun placementWorldTranslation(model: Model, id: Int?): LocalPoint3 {
        val m = localPlacementMatrix(model, id)
        return LocalPoint3(m.m03, m.m13, m.m23)
    }

    private fun axis2Placement3dMatrix(model: Model, id: Int?): Mat4 {
        val axis = model.entity(id) ?: return Mat4.IDENTITY
        if (axis.type != "IFCAXIS2PLACEMENT3D") return Mat4.IDENTITY
        val origin = cartesian3(model, refId(axis.args.getOrNull(0))) ?: LocalPoint3(0.0, 0.0, 0.0)
        val z = direction3(model, refId(axis.args.getOrNull(1))) ?: LocalPoint3(0.0, 0.0, 1.0)
        val x = direction3(model, refId(axis.args.getOrNull(2))) ?: LocalPoint3(1.0, 0.0, 0.0)
        return Mat4.fromAxes(origin, z, x)
    }

    /** (origin, refDirection) of an IfcAxis2Placement2D. */
    private fun axis2Placement2d(model: Model, id: Int?): Pair<LocalPoint2, LocalPoint2> {
        val fallback = LocalPoint2(0.0, 0.0) to LocalPoint2(1.0, 0.0)
        val axis = model.entity(id) ?: return fallback
        if (axis.type != "IFCAXIS2PLACEMENT2D") return fallback
        val origin = cartesian2(model, refId(axis.args.getOrNull(0))) ?: LocalPoint2(0.0, 0.0)
        val dir = direction2(model, refId(axis.args.getOrNull(1))) ?: LocalPoint2(1.0, 0.0)
        return origin to dir
    }

    private fun cartesianTransformationOperator3d(model: Model, op: Entity?): Mat4 {
        op ?: return Mat4.IDENTITY
        // IfcCartesianTransformationOperator3D(Axis1, Axis2, LocalOrigin, Scale, Axis3)
        val origin = cartesian3(model, refId(op.args.getOrNull(2))) ?: LocalPoint3(0.0, 0.0, 0.0)
        val scale = op.args.getOrNull(3)?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1.0
        val x = direction3(model, refId(op.args.getOrNull(0))) ?: LocalPoint3(1.0, 0.0, 0.0)
        val z = direction3(model, refId(op.args.getOrNull(4))) ?: LocalPoint3(0.0, 0.0, 1.0)
        val base = Mat4.fromAxes(origin, z, x)
        if (abs(scale - 1.0) < 1e-9) return base
        return Mat4(
            base.m00 * scale, base.m01 * scale, base.m02 * scale, base.m03,
            base.m10 * scale, base.m11 * scale, base.m12 * scale, base.m13,
            base.m20 * scale, base.m21 * scale, base.m22 * scale, base.m23,
        )
    }

    // --- Profile / curve readers -------------------------------------------------

    private fun readProfileRing(profile: Entity, model: Model): List<LocalPoint2>? {
        return when (profile.type) {
            "IFCARBITRARYCLOSEDPROFILEDEF",
            "IFCARBITRARYPROFILEDEFWITHVOIDS",
            -> {
                val curve = model.entity(refId(profile.args.getOrNull(2)))
                    ?: model.entity(refId(profile.args.lastOrNull()))
                curve?.let { readCurve2d(it, model) }
            }
            "IFCRECTANGLEPROFILEDEF", "IFCRECTANGLEHOLLOWPROFILEDEF" -> rectangleProfile(profile, model)
            "IFCCIRCLEPROFILEDEF", "IFCCIRCLEHOLLOWPROFILEDEF" -> circleProfile(profile, model)
            else -> model.entity(refId(profile.args.lastOrNull()))?.let { readCurve2d(it, model) }
        }
    }

    private fun readCurve2d(curve: Entity, model: Model): List<LocalPoint2>? {
        return when (curve.type) {
            "IFCPOLYLINE" -> polyline2d(curve, model)
            "IFCINDEXEDPOLYCURVE" -> indexedPolyCurve2d(curve, model)
            "IFCCOMPOSITECURVE" -> compositeCurve2d(curve, model)
            else -> null
        }
    }

    private fun polyline2d(curve: Entity, model: Model): List<LocalPoint2>? {
        val refs = splitArgs(curve.args.first().trim('(', ')'))
        val pts = refs.mapNotNull { cartesian2(model, refId(it)) }
        return openLocalRing(pts)
    }

    private fun indexedPolyCurve2d(curve: Entity, model: Model): List<LocalPoint2>? {
        val allPts = cartesianPointList2d(model, refId(curve.args.getOrNull(0))) ?: return null
        if (allPts.isEmpty()) return null
        val segmentsArg = curve.args.getOrNull(1)?.trim().orEmpty()
        if (segmentsArg.isEmpty() || segmentsArg == "\$") return openLocalRing(allPts)
        val indices = Regex("\\d+").findAll(segmentsArg).mapNotNull { it.value.toIntOrNull() }.toList()
        if (indices.isEmpty()) return openLocalRing(allPts)
        return openLocalRing(indices.mapNotNull { idx -> allPts.getOrNull(idx - 1) })
    }

    private fun compositeCurve2d(curve: Entity, model: Model): List<LocalPoint2>? {
        val segmentsRaw = curve.args.firstOrNull()?.trim('(', ')') ?: return null
        val pts = mutableListOf<LocalPoint2>()
        splitArgs(segmentsRaw).forEach { segRef ->
            val segment = model.entity(refId(segRef)) ?: return@forEach
            val parent = model.entity(refId(segment.args.getOrNull(2))) ?: return@forEach
            readCurve2d(parent, model)?.let { part ->
                if (pts.isEmpty()) pts += part else pts += part.drop(1)
            }
        }
        return openLocalRing(pts)
    }

    private fun rectangleProfile(profile: Entity, model: Model): List<LocalPoint2>? {
        val floats = profile.args.mapNotNull { it.toDoubleOrNull() }
        if (floats.size < 2) return null
        val xDim = floats[floats.size - 2]
        val yDim = floats[floats.size - 1]
        val hx = xDim / 2.0
        val hy = yDim / 2.0
        val (origin, refDir) = axis2Placement2d(model, refId(profile.args.firstOrNull { it.startsWith("#") }))
        val dx = normalize2(refDir)
        val dy = LocalPoint2(-dx.y, dx.x)
        fun corner(lx: Double, ly: Double) = LocalPoint2(
            origin.x + dx.x * lx + dy.x * ly,
            origin.y + dx.y * lx + dy.y * ly,
        )
        return listOf(corner(-hx, -hy), corner(hx, -hy), corner(hx, hy), corner(-hx, hy))
    }

    private fun circleProfile(profile: Entity, model: Model): List<LocalPoint2>? {
        val radius = profile.args.mapNotNull { it.toDoubleOrNull() }.lastOrNull() ?: return null
        val (origin, _) = axis2Placement2d(model, refId(profile.args.firstOrNull { it.startsWith("#") }))
        val steps = 24
        return (0 until steps).map { i ->
            val a = 2.0 * PI * i / steps
            LocalPoint2(origin.x + radius * cos(a), origin.y + radius * sin(a))
        }
    }

    private fun cartesianPointList2d(model: Model, id: Int?): List<LocalPoint2>? {
        val entity = model.entity(id) ?: return null
        if (entity.type != "IFCCARTESIANPOINTLIST2D" && entity.type != "IFCCARTESIANPOINTLIST3D") return null
        val body = entity.args.firstOrNull()?.trim() ?: return null
        val pairs = Regex("\\(([^()]+)\\)").findAll(body.removePrefix("(").removeSuffix(")"))
        return pairs.mapNotNull { m ->
            val nums = m.groupValues[1].split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (nums.size >= 2) LocalPoint2(nums[0], nums[1]) else null
        }.toList()
    }

    private fun cartesianPointList3d(model: Model, id: Int?): List<LocalPoint3>? {
        val entity = model.entity(id) ?: return null
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

    private fun cartesian2(model: Model, id: Int?): LocalPoint2? {
        val c = model.coord(id) ?: return null
        if (c.size < 2) return null
        return LocalPoint2(c[0], c[1])
    }

    private fun cartesian3(model: Model, id: Int?): LocalPoint3? {
        val c = model.coord(id) ?: return null
        if (c.size < 2) return null
        return LocalPoint3(c[0], c[1], if (c.size >= 3) c[2] else 0.0)
    }

    private fun direction3(model: Model, id: Int?): LocalPoint3? = cartesian3(model, id)

    private fun direction2(model: Model, id: Int?): LocalPoint2? = cartesian2(model, id)

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

    private fun triangulatedFaceSetToMesh(
        model: Model,
        faceSet: Entity,
        transform: Mat4,
        name: String,
    ): LocalMesh? {
        val points = cartesianPointList3d(model, refId(faceSet.args.getOrNull(0))) ?: return null
        val coordIndexArg = faceSet.args.getOrNull(3) ?: faceSet.args.lastOrNull() ?: return null
        val triangles = mutableListOf<Int>()
        Regex("\\(([^()]+)\\)").findAll(coordIndexArg).forEach { m ->
            val idx = m.groupValues[1].split(',').mapNotNull { it.trim().toIntOrNull() }
            if (idx.size >= 3) triangles += listOf(idx[0] - 1, idx[1] - 1, idx[2] - 1)
        }
        if (triangles.isEmpty()) return null
        return LocalMesh(name, points.map { toScene(transform.transform(it)) }, triangles)
    }

    private fun polygonalFaceSetToMesh(
        model: Model,
        faceSet: Entity,
        transform: Mat4,
        name: String,
    ): LocalMesh? {
        val points = cartesianPointList3d(model, refId(faceSet.args.getOrNull(0))) ?: return null
        val facesArg = faceSet.args.getOrNull(2) ?: return null
        val vertices = points.map { toScene(transform.transform(it)) }
        val indices = mutableListOf<Int>()
        Regex("IFCINDEXEDPOLYGONALFACE\\(\\(([^)]+)\\)").findAll(facesArg).forEach { m ->
            fanIndices(m.groupValues[1], indices)
        }
        if (indices.isEmpty()) {
            Regex("\\((\\d+[\\d,\\s]*)\\)").findAll(facesArg).forEach { m ->
                fanIndices(m.groupValues[1], indices)
            }
        }
        splitArgs(facesArg.trim('(', ')')).forEach { token ->
            val face = model.entity(refId(token)) ?: return@forEach
            if (face.type.startsWith("IFCINDEXEDPOLYGONALFACE")) {
                face.args.firstOrNull()?.let { fanIndices(it.trim('(', ')'), indices) }
            }
        }
        if (indices.isEmpty()) return null
        return LocalMesh(name, vertices, indices)
    }

    private fun facetedBrepToMesh(
        model: Model,
        shell: Entity?,
        transform: Mat4,
        name: String,
    ): LocalMesh? {
        shell ?: return null
        if (shell.type != "IFCCLOSEDSHELL" && shell.type != "IFCOPENSHELL") return null
        val vertices = mutableListOf<Vec3f>()
        val indices = mutableListOf<Int>()
        splitArgs(shell.args.first().trim('(', ')')).forEach { faceTok ->
            val face = model.entity(refId(faceTok)) ?: return@forEach
            val bounds = face.args.firstOrNull() ?: return@forEach
            splitArgs(bounds.trim('(', ')')).forEach { boundTok ->
                val bound = model.entity(refId(boundTok)) ?: return@forEach
                val loop = model.entity(refId(bound.args.getOrNull(0))) ?: return@forEach
                if (loop.type != "IFCPOLYLOOP") return@forEach
                val pts = splitArgs(loop.args.first().trim('(', ')'))
                    .mapNotNull { cartesian3(model, refId(it)) }
                    .map { transform.transform(it) }
                if (pts.size < 3) return@forEach
                val base = vertices.size
                pts.forEach { vertices += toScene(it) }
                for (i in 1 until pts.size - 1) {
                    indices += listOf(base, base + i, base + i + 1)
                    indices += listOf(base, base + i + 1, base + i)
                }
            }
        }
        if (indices.isEmpty()) return null
        return LocalMesh(name, vertices, indices)
    }

    private fun fanIndices(csv: String, out: MutableList<Int>) {
        val idx = csv.split(',').mapNotNull { it.trim().toIntOrNull() }.map { it - 1 }
        if (idx.size < 3) return
        for (i in 1 until idx.size - 1) out += listOf(idx[0], idx[i], idx[i + 1])
    }

    /** Pack many small solids into few renderable chunks, bounded per chunk. */
    private fun mergeMeshes(meshes: List<LocalMesh>): List<LocalMesh> {
        if (meshes.size <= 24) return meshes
        val chunks = mutableListOf<LocalMesh>()
        var vertices = mutableListOf<Vec3f>()
        var indices = mutableListOf<Int>()

        fun flush() {
            if (indices.isEmpty()) return
            chunks += LocalMesh("Grupo ${chunks.size + 1}", vertices, indices)
            vertices = mutableListOf()
            indices = mutableListOf()
        }

        meshes.forEach { mesh ->
            if (vertices.size + mesh.vertices.size > MAX_CHUNK_VERTICES) flush()
            val base = vertices.size
            vertices += mesh.vertices
            mesh.indices.forEach { indices += it + base }
        }
        flush()
        return chunks
    }

    // --- Math --------------------------------------------------------------------

    private fun length(p: LocalPoint3): Double = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
    private fun normalize(p: LocalPoint3): LocalPoint3 {
        val l = length(p)
        return if (l < 1e-12) LocalPoint3(0.0, 0.0, 1.0) else LocalPoint3(p.x / l, p.y / l, p.z / l)
    }
    private fun normalize2(p: LocalPoint2): LocalPoint2 {
        val l = sqrt(p.x * p.x + p.y * p.y)
        return if (l < 1e-12) LocalPoint2(1.0, 0.0) else LocalPoint2(p.x / l, p.y / l)
    }
    private fun cross(a: LocalPoint3, b: LocalPoint3) = LocalPoint3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    )

    // --- STEP reading ------------------------------------------------------------

    private fun readModel(text: String): Model {
        val model = Model()
        forEachStatement(text) { statement ->
            val eq = statement.indexOf('=')
            if (eq < 0) return@forEachStatement
            val open = statement.indexOf('(', eq + 1)
            if (open < 0) return@forEachStatement
            val hash = statement.indexOf('#')
            if (hash < 0 || hash > eq) return@forEachStatement
            val id = statement.substring(hash + 1, eq).trim().toIntOrNull() ?: return@forEachStatement
            val close = statement.lastIndexOf(')')
            if (close <= open) return@forEachStatement
            val type = statement.substring(eq + 1, open).trim().uppercase()
            val body = statement.substring(open + 1, close)

            if (type == "IFCCARTESIANPOINT" || type == "IFCDIRECTION" || type == "IFCVERTEXPOINT") {
                numbersOf(body)?.let { model.coords[id] = it }
                return@forEachStatement
            }
            if (SKIPPED_PREFIXES.any { type.startsWith(it) }) return@forEachStatement
            model.entities[id] = Entity(type, body)
        }
        return model
    }

    private fun numbersOf(body: String): DoubleArray? {
        val inner = body.trim().removePrefix("(").removeSuffix(")")
        val parts = inner.split(',')
        if (parts.size < 2) return null
        val out = DoubleArray(parts.size)
        parts.forEachIndexed { i, part ->
            out[i] = part.trim().toDoubleOrNull() ?: return null
        }
        return out
    }

    private inline fun forEachStatement(text: String, action: (String) -> Unit) {
        val dataStart = text.indexOf("DATA;").let { if (it >= 0) it + 5 else 0 }
        val current = StringBuilder(256)
        var inString = false
        var i = dataStart
        while (i < text.length) {
            val ch = text[i]
            when {
                inString -> {
                    current.append(ch)
                    if (ch == '\'') {
                        if (i + 1 < text.length && text[i + 1] == '\'') {
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
                    val statement = current.toString().trim()
                    current.setLength(0)
                    if (statement.startsWith("#")) action(statement)
                }
                ch == '\n' || ch == '\r' -> current.append(' ')
                else -> current.append(ch)
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.startsWith("#")) action(tail)
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

    // --- Georeferencing ----------------------------------------------------------

    private fun readMapConversion(model: Model): MapConversion? {
        val conversion = model.entities.values.firstOrNull { it.type == "IFCMAPCONVERSION" } ?: return null
        val eastings = conversion.args.getOrNull(2)?.toDoubleOrNull() ?: return null
        val northings = conversion.args.getOrNull(3)?.toDoubleOrNull() ?: return null
        val abscissa = conversion.args.getOrNull(5)?.toDoubleOrNull() ?: 1.0
        val ordinate = conversion.args.getOrNull(6)?.toDoubleOrNull() ?: 0.0
        val scale = conversion.args.getOrNull(7)?.toDoubleOrNull()?.takeIf { it != 0.0 } ?: 1.0
        val crs = model.entity(refId(conversion.args.getOrNull(1)))
            ?: model.entities.values.firstOrNull { it.type == "IFCPROJECTEDCRS" }
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

    /**
     * Angle from the model X axis to true east, measured on the ground. Taken from
     * two projected points instead of the IfcMapConversion angle alone, so it also
     * absorbs the UTM grid convergence that the footprint conversion already has.
     */
    private fun modelToNorthDegrees(conversion: MapConversion): Float {
        val probe = 100.0
        val origin = conversion.toLatLng(0.0, 0.0)
        val alongX = conversion.toLatLng(probe, 0.0)
        val east = Math.toRadians(alongX.longitude - origin.longitude) *
            EARTH_RADIUS_M * cos(Math.toRadians(origin.latitude))
        val north = Math.toRadians(alongX.latitude - origin.latitude) * EARTH_RADIUS_M
        return Math.toDegrees(atan2(north, east)).toFloat()
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

    private fun refId(token: String?): Int? = token?.trim()?.removePrefix("#")?.toIntOrNull()

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
