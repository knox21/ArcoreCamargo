package com.arcoregeo.campoar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class IfcGeoParserTest {

    private val ifc = """
        ISO-10303-21;
        HEADER;
        FILE_SCHEMA(('IFC4'));
        ENDSEC;
        DATA;
        #13=IFCAXIS2PLACEMENT3D(#10,#11,#12);
        #19=IFCSITE('920A',#5,'Site Arequipa','WGS84',${'$'},#18,${'$'},${'$'},.ELEMENT.,(-16,25,13,868286),(-71,31,42,342043),0.,${'$'},${'$'});
        #28=IFCCARTESIANPOINT((-2.077331,-5.140792));
        #29=IFCCARTESIANPOINT((4.843468,-3.020112));
        #30=IFCCARTESIANPOINT((2.165949,4.939924));
        #31=IFCCARTESIANPOINT((-4.932086,3.220979));
        #32=IFCCARTESIANPOINT((-2.077331,-5.140792));
        #33=IFCPOLYLINE((#28,#29,#30,#31,#32));
        #34=IFCARBITRARYCLOSEDPROFILEDEF(.AREA.,'Footprint',#33);
        #35=IFCDIRECTION((0.,0.,1.));
        #36=IFCEXTRUDEDAREASOLID(#34,#13,#35,3.000);
        #40=IFCBUILDINGELEMENTPROXY('0CCB',#5,'Poligono_sin_titulo','Extruded',${'$'},#39,#38,${'$'},.USERDEFINED.);
        ENDSEC;
        END-ISO-10303-21;
    """.trimIndent()

    /** Third-party exporters put a ';' inside the site description. */
    private val ifcWithSemicolonInText = """
        ISO-10303-21;
        HEADER;
        FILE_SCHEMA(('IFC4'));
        ENDSEC;
        DATA;
        #16=IFCPROJECTEDCRS('WGS 84 / UTM zone 19S','EPSG:32719','WGS84',${'$'},'UTM','19S',#10);
        #17=IFCMAPCONVERSION(#15,#16,229978.386950,8182863.234587,0.000,1.,0.,1.);
        #22=IFCAXIS2PLACEMENT3D(#21,${'$'},${'$'});
        #24=IFCSITE('0rCi',#5,'Georeferenced KML Site','WGS84 source; projected to EPSG:32719',${'$'},#23,${'$'},${'$'},.ELEMENT.,(-16,25,13,868286),(-71,31,42,342043),0.,${'$'},${'$'});
        #40=IFCCARTESIANPOINT((-2.077331,-5.140792));
        #41=IFCCARTESIANPOINT((4.843468,-3.020112));
        #42=IFCCARTESIANPOINT((2.165949,4.939925));
        #43=IFCCARTESIANPOINT((-4.932086,3.220979));
        #44=IFCPOLYLINE((#40,#41,#42,#43,#40));
        #45=IFCARBITRARYCLOSEDPROFILEDEF(.AREA.,'KML Footprint',#44);
        #49=IFCEXTRUDEDAREASOLID(#45,#47,#48,3.000);
        #52=IFCBUILDINGELEMENTPROXY('2ruC',#5,'KML Solid 3m','Exact KML footprint extruded vertically 3.00 m','Georeferenced solid',#32,#51,'KML-3M',.NOTDEFINED.);
        ENDSEC;
        END-ISO-10303-21;
    """.trimIndent()

    /** Revit / Civil 3D style: georeferencing only through IfcMapConversion. */
    private val ifcOnlyMapConversion = """
        ISO-10303-21;
        DATA;
        #16=IFCPROJECTEDCRS('EPSG:32719','WGS 84 / UTM zone 19S',${'$'},'EPSG','32719',${'$'},#10);
        #17=IFCMAPCONVERSION(#15,#16,229978.386950,8182863.234587,0.000,1.,0.,1.);
        #40=IFCCARTESIANPOINT((0.,0.));
        #41=IFCCARTESIANPOINT((10.,0.));
        #42=IFCCARTESIANPOINT((10.,10.));
        #43=IFCCARTESIANPOINT((0.,10.));
        #44=IFCPOLYLINE((#40,#41,#42,#43,#40));
        #45=IFCARBITRARYCLOSEDPROFILEDEF(.AREA.,'Footprint',#44);
        #49=IFCEXTRUDEDAREASOLID(#45,#47,#48,3.000);
        ENDSEC;
        END-ISO-10303-21;
    """.trimIndent()

    @Test
    fun `georeferences through map conversion when site angles are missing`() {
        val doc = IfcGeoParser.parse("civil3d.ifc", ifcOnlyMapConversion)
        val first = doc.polygons.first().ring.first()

        // UTM 19S E=229978.387 N=8182863.235 is the Arequipa plot origin.
        assertTrue("lat was ${first.latitude}", abs(first.latitude - (-16.42051897)) < 1e-6)
        assertTrue("lon was ${first.longitude}", abs(first.longitude - (-71.52842835)) < 1e-6)

        val ring = openRing(doc.polygons.first().ring)
        val edge = GeoDistance.meters(ring[0], ring[1])
        assertTrue("edge was $edge m", abs(edge - 10.0) < 0.1)
    }

    @Test
    fun `reads site coordinates when descriptions contain semicolons`() {
        val doc = IfcGeoParser.parse("solido_kml_georreferenciado_3m.ifc", ifcWithSemicolonInText)

        val centroid = centroidOf(openRing(doc.polygons.first().ring))!!
        assertEquals("KML Solid 3m", doc.polygons.first().name)
        assertEquals(3f, doc.solidHeightMeters)
        assertTrue(
            "centroid was ${centroid.latitude}, ${centroid.longitude}",
            abs(centroid.latitude - (-16.42051897)) < 1e-4 &&
                abs(centroid.longitude - (-71.52842835)) < 1e-4,
        )
    }

    @Test
    fun `reads georeferenced footprint from ifc`() {
        val doc = IfcGeoParser.parse("Poligono_sin_titulo_solid.ifc", ifc)

        assertEquals(1, doc.polygons.size)
        assertEquals(3f, doc.solidHeightMeters)
        assertEquals("Poligono_sin_titulo", doc.polygons.first().name)

        val ring = openRing(doc.polygons.first().ring)
        assertEquals(4, ring.size)

        val centroid = centroidOf(ring)!!
        assertTrue(
            "centroid lat was ${centroid.latitude}",
            abs(centroid.latitude - (-16.42051897)) < 1e-4,
        )
        assertTrue(
            "centroid lon was ${centroid.longitude}",
            abs(centroid.longitude - (-71.52842835)) < 1e-4,
        )

        // Footprint is roughly 10 m across.
        val width = GeoDistance.meters(ring[0], ring[1])
        assertTrue("edge was $width m", width in 3.0..20.0)
    }

    private object GeoDistance {
        fun meters(a: LatLngAlt, b: LatLngAlt): Double {
            val dLat = Math.toRadians(b.latitude - a.latitude) * 6_378_137.0
            val dLon = Math.toRadians(b.longitude - a.longitude) * 6_378_137.0 *
                kotlin.math.cos(Math.toRadians(a.latitude))
            return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
        }
    }
}
