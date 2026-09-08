package com.arcoregeo.campoar.data

import com.arcoregeo.campoar.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KmzParserTest {
    @Test
    fun parsesPointsAndLineString() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <Placemark>
                  <name>Mojon 12</name>
                  <Point><coordinates>-99.13,19.43,2240</coordinates></Point>
                </Placemark>
                <Placemark>
                  <name>Linde</name>
                  <LineString>
                    <coordinates>
                      -99.13,19.43,0
                      -99.14,19.44,0
                    </coordinates>
                  </LineString>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()

        val document = KmzParser.parseKml("predio.kml", xml)
        assertEquals(1, document.points.size)
        assertEquals("Mojon 12", document.points[0].name)
        assertEquals(19.43, document.points[0].coordinate.latitude, 0.0001)
        assertEquals(-99.13, document.points[0].coordinate.longitude, 0.0001)
        assertEquals(2240.0, document.points[0].coordinate.altitude!!, 0.1)
        assertEquals(1, document.lines.size)
        assertEquals(2, document.lines[0].coordinates.size)
    }

    @Test
    fun parsesGoogleEarthPolygon() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <Placemark>
                  <name>Predio</name>
                  <Polygon>
                    <outerBoundaryIs>
                      <LinearRing>
                        <coordinates>
                          -99.1332,19.4330,0
                          -99.1328,19.4330,0
                          -99.1328,19.4326,0
                          -99.1332,19.4326,0
                          -99.1332,19.4330,0
                        </coordinates>
                      </LinearRing>
                    </outerBoundaryIs>
                  </Polygon>
                </Placemark>
              </Document>
            </kml>
        """.trimIndent()

        val document = KmzParser.parseKml("poligono.kml", xml)
        assertEquals(0, document.points.size)
        assertEquals(1, document.polygons.size)
        assertEquals("Predio", document.polygons[0].name)
        assertTrue(document.polygons[0].ring.size >= 4)
        val targets = document.arTargets()
        assertTrue(targets.size >= 4)
        assertTrue(targets.any { it.name.contains("Predio") })
    }

    @Test
    fun distanceBetweenClosePointsIsTensOfMeters() {
        val from = LatLngAlt(19.4324, -99.1335)
        val to = LatLngAlt(19.4330, -99.1332)
        val meters = GeoMath.distanceMeters(from, to)
        assertTrue(meters in 50.0..120.0)
        val bearing = GeoMath.bearingDegrees(from, to)
        assertTrue(bearing in 0.0..90.0)
    }
}
