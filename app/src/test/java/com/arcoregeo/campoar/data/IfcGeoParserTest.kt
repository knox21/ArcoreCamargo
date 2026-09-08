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
        assertTrue(doc.isGeoreferenced)
        assertTrue(doc.localMeshes.isNotEmpty())
        assertTrue(doc.localMeshes.first().indices.size >= 12)

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

    private val ifcIndexedPolyCurve = """
        ISO-10303-21;
        DATA;
        #1=IFCCARTESIANPOINTLIST2D(((0.,0.),(8.,0.),(8.,5.),(0.,5.)));
        #2=IFCINDEXEDPOLYCURVE(#1,(IFCLINEINDEX((1,2,3,4,1))),.F.);
        #3=IFCARBITRARYCLOSEDPROFILEDEF(.AREA.,'Box',#2);
        #4=IFCEXTRUDEDAREASOLID(#3,#5,#6,2.500);
        ENDSEC;
        END-ISO-10303-21;
    """.trimIndent()

    @Test
    fun `imports non georeferenced indexed polycurve as local mesh`() {
        val doc = IfcGeoParser.parse("local_box.ifc", ifcIndexedPolyCurve)
        assertEquals("ifc", doc.sourceKind)
        assertTrue(!doc.isGeoreferenced)
        assertTrue(doc.polygons.isEmpty())
        assertEquals(1, doc.localMeshes.size)
        assertEquals(2.5f, doc.solidHeightMeters)
        assertTrue(doc.localMeshes.first().vertices.size >= 4)
    }

    private val ifcTriangulated = """
        ISO-10303-21;
        DATA;
        #1=IFCCARTESIANPOINTLIST3D(((0.,0.,0.),(1.,0.,0.),(0.,1.,0.),(0.,0.,1.)));
        #2=IFCTRIANGULATEDFACESET(#1,${'$'},.T.,((1,2,3),(1,2,4),(1,3,4),(2,3,4)),${'$'});
        ENDSEC;
        END-ISO-10303-21;
    """.trimIndent()

    @Test
    fun `imports triangulated face set without georef`() {
        val doc = IfcGeoParser.parse("tet.ifc", ifcTriangulated)
        assertTrue(doc.localMeshes.isNotEmpty())
        assertEquals(4, doc.localMeshes.first().vertices.size)
        assertEquals(12, doc.localMeshes.first().indices.size)
        assertTrue(!doc.isGeoreferenced)
    }

    /** Minimal Revit IFC2X3 WallStandardCase + RectangleProfile + site placement. */
    private val ifcRevitWall = """
        ISO-10303-21;
        HEADER;
        FILE_SCHEMA(('IFC2X3'));
        ENDSEC;
        DATA;
        #6=IFCCARTESIANPOINT((0.,0.,0.));
        #9=IFCCARTESIANPOINT((0.,0.));
        #11=IFCDIRECTION((1.,0.,0.));
        #17=IFCDIRECTION((0.,-1.,0.));
        #19=IFCDIRECTION((0.,0.,1.));
        #25=IFCDIRECTION((-1.,0.));
        #31=IFCAXIS2PLACEMENT3D(#6,${'$'},${'$'});
        #32=IFCLOCALPLACEMENT(#163,#31);
        #135=IFCCARTESIANPOINT((0.,0.,-2.));
        #137=IFCAXIS2PLACEMENT3D(#135,${'$'},${'$'});
        #138=IFCLOCALPLACEMENT(#32,#137);
        #158=IFCCARTESIANPOINT((273258.394040389,8686440.36643775,181.));
        #160=IFCDIRECTION((0.542926549111797,0.83978018687604,0.));
        #162=IFCAXIS2PLACEMENT3D(#158,#19,#160);
        #163=IFCLOCALPLACEMENT(${'$'},#162);
        #164=IFCSITE('site',${'$'},'Default',${'$'},${'$'},#163,${'$'},${'$'},.ELEMENT.,(-11,-52,-29,-240089),(-77,-4,-53,-665776),180.999999999997,${'$'},${'$'});
        #195=IFCCARTESIANPOINT((96.3771213923411,29.150972090348,1.));
        #197=IFCAXIS2PLACEMENT3D(#195,#19,#17);
        #198=IFCLOCALPLACEMENT(#138,#197);
        #200=IFCCARTESIANPOINT((1.22231154075829,0.));
        #202=IFCPOLYLINE((#9,#200));
        #204=IFCSHAPEREPRESENTATION(${'$'},'Axis','Curve2D',(#202));
        #207=IFCCARTESIANPOINT((0.611155770379146,0.));
        #209=IFCAXIS2PLACEMENT2D(#207,#25);
        #210=IFCRECTANGLEPROFILEDEF(.AREA.,${'$'},#209,1.22231154075829,0.250000000000012);
        #211=IFCAXIS2PLACEMENT3D(#6,${'$'},${'$'});
        #212=IFCEXTRUDEDAREASOLID(#210,#211,#19,1.49999999999863);
        #222=IFCSHAPEREPRESENTATION(${'$'},'Body','SweptSolid',(#212));
        #225=IFCPRODUCTDEFINITIONSHAPE(${'$'},${'$'},(#204,#222));
        #229=IFCWALLSTANDARDCASE('wall1',${'$'},'Basic Wall:MUROS',${'$'},'Basic Wall',#198,#225,'1955589');
        ENDSEC;
        END-ISO-10303-21;
    """.trimIndent()

    @Test
    fun `imports Revit IFC2X3 wall with placement and cancels huge site coords`() {
        val doc = IfcGeoParser.parse("cmi_santa_rosa.ifc", ifcRevitWall)
        assertTrue(doc.localMeshes.isNotEmpty())
        assertTrue(doc.solidHeightMeters!! > 1f)
        val verts = doc.localMeshes.flatMap { it.vertices }
        val maxAbs = verts.maxOf { maxOf(abs(it.x), abs(it.y), abs(it.z)) }
        // Without site cancel this would be ~millions of meters.
        assertTrue("coords too large: $maxAbs", maxAbs < 5_000f)
        // Wall sits near (96, 29) in building local → scene Y-up so X/Z roughly that scale.
        assertTrue("expected wall near project base, got $maxAbs", maxAbs > 10f)
        assertTrue(doc.isGeoreferenced)
        assertTrue(doc.polygons.isNotEmpty())
    }

    @Test
    fun `unwraps boolean clipping to extruded solid`() {
        val ifc = """
            ISO-10303-21;
            DATA;
            #1=IFCCARTESIANPOINTLIST2D(((0.,0.),(4.,0.),(4.,2.),(0.,2.)));
            #2=IFCINDEXEDPOLYCURVE(#1,(IFCLINEINDEX((1,2,3,4,1))),.F.);
            #3=IFCARBITRARYCLOSEDPROFILEDEF(.AREA.,'P',#2);
            #4=IFCEXTRUDEDAREASOLID(#3,${'$'},${'$'},3.0);
            #5=IFCBOOLEANCLIPPINGRESULT(.DIFFERENCE.,#4,#4);
            #6=IFCSHAPEREPRESENTATION(${'$'},'Body','SweptSolid',(#5));
            #7=IFCPRODUCTDEFINITIONSHAPE(${'$'},${'$'},(#6));
            #8=IFCWALL('w',${'$'},'Muro clipped',${'$'},${'$'},${'$'},#7,${'$'});
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()
        val doc = IfcGeoParser.parse("clipped.ifc", ifc)
        assertTrue(doc.localMeshes.isNotEmpty())
        assertEquals(3f, doc.solidHeightMeters)
    }

    @Test
    fun `keeps a complex building within the render budget`() {
        val walls = 4_000
        val ifc = buildString {
            appendLine("ISO-10303-21;")
            appendLine("HEADER;")
            appendLine("FILE_SCHEMA(('IFC2X3'));")
            appendLine("ENDSEC;")
            appendLine("DATA;")
            appendLine("#1=IFCCARTESIANPOINT((0.,0.,0.));")
            appendLine("#2=IFCDIRECTION((0.,0.,1.));")
            appendLine("#3=IFCDIRECTION((1.,0.));")
            appendLine("#4=IFCAXIS2PLACEMENT3D(#1,${'$'},${'$'});")
            var id = 100
            repeat(walls) { i ->
                val origin = id++
                val axis2d = id++
                val place3d = id++
                val local = id++
                val profile = id++
                val solid = id++
                val rep = id++
                val shape = id++
                val wall = id++
                val propertySet = id++
                val relation = id++
                appendLine("#$origin=IFCCARTESIANPOINT((${i * 2}.,0.,0.));")
                appendLine("#$axis2d=IFCAXIS2PLACEMENT2D(#1,#3);")
                appendLine("#$place3d=IFCAXIS2PLACEMENT3D(#$origin,${'$'},${'$'});")
                appendLine("#$local=IFCLOCALPLACEMENT(${'$'},#$place3d);")
                appendLine("#$profile=IFCRECTANGLEPROFILEDEF(.AREA.,${'$'},#$axis2d,2.,0.3);")
                appendLine("#$solid=IFCEXTRUDEDAREASOLID(#$profile,#4,#2,2.7);")
                appendLine("#$rep=IFCSHAPEREPRESENTATION(${'$'},'Body','SweptSolid',(#$solid));")
                appendLine("#$shape=IFCPRODUCTDEFINITIONSHAPE(${'$'},${'$'},(#$rep));")
                appendLine("#$wall=IFCWALLSTANDARDCASE('w$i',${'$'},'Muro $i',${'$'},${'$'},#$local,#$shape,'$i');")
                appendLine("#$propertySet=IFCPROPERTYSINGLEVALUE('Ancho',${'$'},IFCREAL(0.3),${'$'});")
                appendLine("#$relation=IFCRELDEFINESBYPROPERTIES('r$i',${'$'},${'$'},${'$'},(#$wall),#$propertySet);")
            }
            appendLine("ENDSEC;")
            appendLine("END-ISO-10303-21;")
        }

        val doc = IfcGeoParser.parse("edificio.ifc", ifc)
        val vertices = doc.localMeshes.sumOf { it.vertices.size }
        assertTrue("no geometry", vertices > 0)
        assertTrue("vertex budget exceeded: $vertices", vertices <= 150_000)
        assertTrue("too many draw calls: ${doc.localMeshes.size}", doc.localMeshes.size <= 40)
        doc.localMeshes.forEach { mesh ->
            val maxIndex = mesh.indices.max()
            assertTrue("index out of range: $maxIndex", maxIndex < mesh.vertices.size)
        }
    }

    private fun openRing(ring: List<LatLngAlt>): List<LatLngAlt> {
        if (ring.size < 2) return ring
        val a = ring.first()
        val b = ring.last()
        return if (abs(a.latitude - b.latitude) < 1e-12 && abs(a.longitude - b.longitude) < 1e-12) {
            ring.dropLast(1)
        } else {
            ring
        }
    }

    private fun centroidOf(ring: List<LatLngAlt>): LatLngAlt? {
        if (ring.isEmpty()) return null
        return LatLngAlt(
            ring.map { it.latitude }.average(),
            ring.map { it.longitude }.average(),
        )
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
