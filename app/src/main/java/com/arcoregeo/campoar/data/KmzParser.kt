package com.arcoregeo.campoar.data

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object KmzParser {
    fun parse(fileName: String, input: InputStream): KmzDocument {
        val bytes = input.readBytes()
        val kmlXml = if (fileName.endsWith(".kmz", ignoreCase = true) || looksLikeZip(bytes)) {
            extractKmlFromKmz(bytes)
        } else {
            bytes.decodeToString()
        }
        return parseKml(fileName, kmlXml)
    }

    fun parseKml(fileName: String, xml: String): KmzDocument {
        val cleaned = xml.trim().removePrefix("\uFEFF")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isIgnoringComments = true
            isExpandEntityReferences = false
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(cleaned.toByteArray(Charsets.UTF_8)))
        val root = document.documentElement
        val points = mutableListOf<GeoPoint>()
        val lines = mutableListOf<GeoLine>()
        val polygons = mutableListOf<GeoPolygon>()
        walkPlacemarks(root, points, lines, polygons)
        return KmzDocument(
            id = UUID.randomUUID().toString(),
            fileName = FileNames.sanitize(fileName, "archivo.kml"),
            storedFileName = "",
            importedAtEpochMs = System.currentTimeMillis(),
            points = points,
            lines = lines,
            polygons = polygons,
        )
    }

    private fun walkPlacemarks(
        node: Node,
        points: MutableList<GeoPoint>,
        lines: MutableList<GeoLine>,
        polygons: MutableList<GeoPolygon>,
    ) {
        if (node is Element && node.kmlName().equals("Placemark", ignoreCase = true)) {
            parsePlacemark(node, points, lines, polygons)
        }
        node.childNodes.forEachElement { walkPlacemarks(it, points, lines, polygons) }
    }

    private fun parsePlacemark(
        placemark: Element,
        points: MutableList<GeoPoint>,
        lines: MutableList<GeoLine>,
        polygons: MutableList<GeoPolygon>,
    ) {
        val name = firstChildText(placemark, "name") ?: "Marca ${points.size + lines.size + polygons.size + 1}"
        val description = firstChildText(placemark, "description").orEmpty()
        val id = placemark.getAttribute("id").ifBlank { UUID.randomUUID().toString() }

        // Collect every geometry Google Earth may nest (Point, Polygon, MultiGeometry, etc.)
        collectGeometry(placemark, id, name, description, points, lines, polygons)
    }

    private fun collectGeometry(
        element: Element,
        id: String,
        name: String,
        description: String,
        points: MutableList<GeoPoint>,
        lines: MutableList<GeoLine>,
        polygons: MutableList<GeoPolygon>,
    ) {
        when (element.kmlName().lowercase()) {
            "point" -> {
                parseCoordinates(element).firstOrNull()?.let { coord ->
                    points += GeoPoint(id = "$id-p${points.size}", name = name, description = description, coordinate = coord)
                }
            }
            "linestring", "linearring" -> {
                // LinearRing under Polygon is handled by polygon path; standalone rare.
                if (!isInsidePolygon(element)) {
                    val coords = parseCoordinates(element)
                    if (coords.size >= 2) {
                        lines += GeoLine(id = "$id-l${lines.size}", name = name, coordinates = coords)
                    }
                }
            }
            "polygon" -> {
                val coords = parsePolygonRing(element)
                if (coords.size >= 3) {
                    polygons += GeoPolygon(id = "$id-g${polygons.size}", name = name, ring = coords)
                }
            }
            "multigeometry", "placemark", "document", "folder", "kml" -> {
                element.childElements().forEach { child ->
                    collectGeometry(child, id, name, description, points, lines, polygons)
                }
            }
            else -> {
                // gx:Track / SchemaData etc. — keep walking children for nested geometries.
                element.childElements().forEach { child ->
                    val childName = child.kmlName().lowercase()
                    if (childName in GEOMETRY_TAGS || childName in CONTAINER_TAGS) {
                        collectGeometry(child, id, name, description, points, lines, polygons)
                    }
                }
            }
        }
    }

    private fun isInsidePolygon(element: Element): Boolean {
        var parent = element.parentNode
        while (parent != null) {
            if (parent is Element && parent.kmlName().equals("Polygon", ignoreCase = true)) return true
            parent = parent.parentNode
        }
        return false
    }

    private fun parsePolygonRing(polygon: Element): List<LatLngAlt> {
        val outer = polygon.descendantByName("outerBoundaryIs")
            ?.descendantByName("LinearRing")
            ?: polygon.descendantByName("LinearRing")
            ?: polygon
        return parseCoordinates(outer)
    }

    private fun parseCoordinates(geometry: Element?): List<LatLngAlt> {
        if (geometry == null) return emptyList()
        val text = geometry.descendantByName("coordinates")?.textContent
            ?: if (geometry.kmlName().equals("coordinates", ignoreCase = true)) geometry.textContent else null
            ?: return emptyList()
        // Google Earth may separate tuples by space, newline, or tab.
        return text.trim()
            .split(Regex("[\\s]+"))
            .map { it.trim().trimEnd(',') }
            .filter { it.contains(",") }
            .mapNotNull { token ->
                val parts = token.split(",")
                val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                if (lon !in -180.0..180.0 || lat !in -90.0..90.0) return@mapNotNull null
                val alt = parts.getOrNull(2)?.toDoubleOrNull()
                LatLngAlt(latitude = lat, longitude = lon, altitude = alt)
            }
    }

    private fun extractKmlFromKmz(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val kmlEntries = mutableListOf<Pair<String, String>>()
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.endsWith(".kml", ignoreCase = true)) {
                    kmlEntries += entry.name to zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
            }
            return kmlEntries.firstOrNull { it.first.substringAfterLast('/').equals("doc.kml", true) }?.second
                ?: kmlEntries.minByOrNull { it.first.length }?.second
                ?: error("El KMZ no contiene un archivo KML")
        }
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte()
    }

    private fun firstChildText(element: Element, localName: String): String? {
        return element.childElements()
            .firstOrNull { it.kmlName().equals(localName, ignoreCase = true) }
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private val GEOMETRY_TAGS = setOf("point", "linestring", "linearring", "polygon", "multigeometry")
    private val CONTAINER_TAGS = setOf("placemark", "document", "folder", "kml", "outerboundaryis", "innerboundaryis")
}

private fun Element.kmlName(): String {
    val local = localName
    if (!local.isNullOrBlank()) return local
    return tagName.substringAfter(':')
}

private fun Element.descendantByName(name: String): Element? {
    if (kmlName().equals(name, ignoreCase = true)) return this
    childElements().forEach { child ->
        child.descendantByName(name)?.let { return it }
    }
    return null
}

private fun Element.childElements(): List<Element> = childNodes.toElementList()

private fun NodeList.forEachElement(block: (Element) -> Unit) {
    toElementList().forEach(block)
}

private fun NodeList.toElementList(): List<Element> {
    return (0 until length).mapNotNull { item(it) as? Element }
}
