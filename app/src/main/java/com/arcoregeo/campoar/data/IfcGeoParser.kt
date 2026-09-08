package com.arcoregeo.campoar.data

import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Minimal STEP/IFC4 reader for georeferenced extruded solids. The footprint comes from
 * the swept profile polyline (local metres) and is placed either through
 * IfcMapConversion + IfcProjectedCRS (UTM) or, failing that, through the
 * IfcSite RefLatitude/RefLongitude taken as local origin.
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

    fun parse(fileName: String, text: String): KmzDocument {
        val entities = readEntities(text)

        val site = entities.values.firstOrNull { it.type == "IFCSITE" }
        val siteOrigin = site?.let {
            val latitude = compoundAngle(it.args.getOrNull(9))
            val longitude = compoundAngle(it.args.getOrNull(10))
            if (latitude != null && longitude != null) LatLngAlt(latitude, longitude) else null
        }
        val mapConversion = readMapConversion(entities)
        if (siteOrigin == null && mapConversion == null) {
            error(
                "El IFC no está georreferenciado: falta IfcSite RefLatitude/RefLongitude " +
                    "y también IfcMapConversion con un CRS UTM",
            )
        }

        val solid = entities.values.firstOrNull { it.type == "IFCEXTRUDEDAREASOLID" }
            ?: error("El IFC no contiene un sólido extruido (IfcExtrudedAreaSolid)")
        val height = solid.args.getOrNull(3)?.toFloatOrNull() ?: 3f

        val profile = solid.args.getOrNull(0)?.let { entities[refId(it)] }
            ?: error("No se encontró el perfil del sólido")
        val curve = profile.args.lastOrNull()?.let { entities[refId(it)] }
            ?: error("El perfil del IFC no es una polilínea")
        if (curve.type != "IFCPOLYLINE") {
            error("El contorno del IFC no es una IfcPolyline (${curve.type})")
        }

        val ring = splitArgs(curve.args.first().trim('(', ')')).mapNotNull { ref ->
            val point = entities[refId(ref)] ?: return@mapNotNull null
            val coords = splitArgs(point.args.first().trim('(', ')'))
            val east = coords.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val north = coords.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            when {
                mapConversion != null -> mapConversion.toLatLng(east, north)
                else -> fromEnu(siteOrigin!!, east, north)
            }
        }
        if (ring.size < 3) error("El contorno del IFC tiene menos de 3 vértices")

        val name = entities.values.firstOrNull { it.type == "IFCBUILDINGELEMENTPROXY" }
            ?.args?.getOrNull(2)?.stepText()
            ?: fileName.substringBeforeLast('.')

        return KmzDocument(
            id = UUID.randomUUID().toString(),
            fileName = fileName.substringAfterLast('/').substringAfterLast(':'),
            storedFileName = "",
            importedAtEpochMs = System.currentTimeMillis(),
            points = emptyList(),
            lines = emptyList(),
            polygons = listOf(
                GeoPolygon(id = UUID.randomUUID().toString(), name = name, ring = ring),
            ),
            solidHeightMeters = height,
        )
    }

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

    /** Statements end at ';', but descriptions may contain one inside a quoted string. */
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

    /** Split STEP arguments honouring nested parentheses and quoted strings. */
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

    /** Reads the UTM zone from the EPSG code, or from a "zone 19S" style label. */
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

    /** Inverse transverse Mercator (WGS84 ellipsoid). */
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

    /** IfcCompoundPlaneAngleMeasure: (degrees, minutes, seconds, millionths). */
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
