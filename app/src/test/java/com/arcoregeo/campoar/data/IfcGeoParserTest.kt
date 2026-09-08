package com.arcoregeo.campoar.data

import com.arcoregeo.campoar.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    /** Writes a Revit-shaped export: every wall carries placement, profile and props. */
    private fun writeSyntheticBuilding(target: File, walls: Int) {
        target.bufferedWriter(Charsets.ISO_8859_1).use { out ->
            out.appendLine("ISO-10303-21;")
            out.appendLine("HEADER;")
            out.appendLine("FILE_SCHEMA(('IFC2X3'));")
            out.appendLine("ENDSEC;")
            out.appendLine("DATA;")
            out.appendLine("#1=IFCCARTESIANPOINT((0.,0.,0.));")
            out.appendLine("#2=IFCDIRECTION((0.,0.,1.));")
            out.appendLine("#3=IFCDIRECTION((1.,0.));")
            out.appendLine("#4=IFCAXIS2PLACEMENT3D(#1,${'$'},${'$'});")
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
                out.appendLine("#$origin=IFCCARTESIANPOINT((${i % 200}.,${i / 200}.,0.));")
                out.appendLine("#$axis2d=IFCAXIS2PLACEMENT2D(#1,#3);")
                out.appendLine("#$place3d=IFCAXIS2PLACEMENT3D(#$origin,${'$'},${'$'});")
                out.appendLine("#$local=IFCLOCALPLACEMENT(${'$'},#$place3d);")
                out.appendLine("#$profile=IFCRECTANGLEPROFILEDEF(.AREA.,${'$'},#$axis2d,2.,0.3);")
                out.appendLine("#$solid=IFCEXTRUDEDAREASOLID(#$profile,#4,#2,2.7);")
                out.appendLine("#$rep=IFCSHAPEREPRESENTATION(${'$'},'Body','SweptSolid',(#$solid));")
                out.appendLine("#$shape=IFCPRODUCTDEFINITIONSHAPE(${'$'},${'$'},(#$rep));")
                out.appendLine(
                    "#$wall=IFCWALLSTANDARDCASE('guid$i',#5,'Basic Wall:Muro básico:$i'," +
                        "${'$'},'Basic Wall:Muro básico',#$local,#$shape,'$i');",
                )
                out.appendLine("#$propertySet=IFCPROPERTYSINGLEVALUE('Ancho',${'$'},IFCREAL(0.3),${'$'});")
                out.appendLine(
                    "#$relation=IFCRELDEFINESBYPROPERTIES('r$i',#5,${'$'},${'$'},(#$wall),#$propertySet);",
                )
            }
            out.appendLine("ENDSEC;")
            out.appendLine("END-ISO-10303-21;")
        }
    }

    /**
     * The reader runs inside a phone heap (see the test JVM budget in build.gradle),
     * so importing an export of this size has to finish without hoarding it.
     */
    @Test
    fun `imports a Revit sized export inside a phone heap`() {
        val file = File.createTempFile("edificio", ".ifc")
        try {
            writeSyntheticBuilding(file, walls = 20_000)
            assertTrue("file was ${file.length()} bytes", file.length() > 8L * 1024 * 1024)

            val doc = file.bufferedReader(Charsets.ISO_8859_1).use {
                IfcGeoParser.parse("edificio.ifc", it)
            }

            assertTrue("no geometry", doc.localMeshes.isNotEmpty())
            assertTrue("note was ${doc.geometryNote}", doc.geometryNote!!.contains("20000 elementos"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `keeps a complex building within the render budget`() {
        val file = File.createTempFile("budget", ".ifc")
        val doc = try {
            writeSyntheticBuilding(file, walls = 4_000)
            file.bufferedReader(Charsets.ISO_8859_1).use { IfcGeoParser.parse("edificio.ifc", it) }
        } finally {
            file.delete()
        }
        val vertices = doc.localMeshes.sumOf { it.vertices.size }
        assertTrue("no geometry", vertices > 0)
        assertTrue("vertex budget exceeded: $vertices", vertices <= 150_000)
        assertTrue("too many draw calls: ${doc.localMeshes.size}", doc.localMeshes.size <= 40)
        doc.localMeshes.forEach { mesh ->
            val maxIndex = mesh.indices.max()
            assertTrue("index out of range: $maxIndex", maxIndex < mesh.vertices.size)
        }
    }

    /** IFC4 Revit exports describe curved solids as advanced BReps with edge loops. */
    @Test
    fun `reads an advanced brep face through its edge loop`() {
        val ifc = """
            ISO-10303-21;
            DATA;
            #1=IFCCARTESIANPOINT((0.,0.,0.));
            #2=IFCCARTESIANPOINT((4.,0.,0.));
            #3=IFCCARTESIANPOINT((4.,3.,0.));
            #4=IFCCARTESIANPOINT((0.,3.,0.));
            #11=IFCVERTEXPOINT(#1);
            #12=IFCVERTEXPOINT(#2);
            #13=IFCVERTEXPOINT(#3);
            #14=IFCVERTEXPOINT(#4);
            #21=IFCEDGECURVE(#11,#12,#31,.T.);
            #22=IFCEDGECURVE(#12,#13,#31,.T.);
            #23=IFCEDGECURVE(#13,#14,#31,.T.);
            #24=IFCEDGECURVE(#14,#11,#31,.T.);
            #41=IFCORIENTEDEDGE(*,*,#21,.T.);
            #42=IFCORIENTEDEDGE(*,*,#22,.T.);
            #43=IFCORIENTEDEDGE(*,*,#23,.T.);
            #44=IFCORIENTEDEDGE(*,*,#24,.T.);
            #51=IFCEDGELOOP((#41,#42,#43,#44));
            #52=IFCFACEOUTERBOUND(#51,.T.);
            #53=IFCADVANCEDFACE((#52),#60,.T.);
            #54=IFCCLOSEDSHELL((#53));
            #55=IFCADVANCEDBREP(#54);
            #56=IFCSHAPEREPRESENTATION(${'$'},'Body','AdvancedBrep',(#55));
            #57=IFCPRODUCTDEFINITIONSHAPE(${'$'},${'$'},(#56));
            #58=IFCSLAB('slab',${'$'},'Losa curva',${'$'},${'$'},${'$'},#57,${'$'},.FLOOR.);
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        val doc = IfcGeoParser.parse("advanced.ifc", ifc)
        assertEquals(4, doc.localMeshes.sumOf { it.vertices.size })
        assertTrue(doc.localMeshes.first().indices.size >= 6)
    }

    /** Structural models are full of steel sections the reader has no exact profile for. */
    @Test
    fun `approximates a parametric steel profile with its overall size`() {
        val ifc = """
            ISO-10303-21;
            DATA;
            #1=IFCCARTESIANPOINT((0.,0.,0.));
            #2=IFCDIRECTION((0.,0.,1.));
            #3=IFCAXIS2PLACEMENT3D(#1,${'$'},${'$'});
            #4=IFCISHAPEPROFILEDEF(.AREA.,'W310X39',${'$'},0.3,0.6,0.012,0.019,0.01);
            #5=IFCEXTRUDEDAREASOLID(#4,#3,#2,4.);
            #6=IFCSHAPEREPRESENTATION(${'$'},'Body','SweptSolid',(#5));
            #7=IFCPRODUCTDEFINITIONSHAPE(${'$'},${'$'},(#6));
            #8=IFCCOLUMN('col',${'$'},'Columna W310',${'$'},${'$'},${'$'},#7,${'$'});
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        val doc = IfcGeoParser.parse("acero.ifc", ifc)
        val verts = doc.localMeshes.flatMap { it.vertices }
        assertTrue("no geometry", verts.isNotEmpty())
        val width = verts.maxOf { it.x } - verts.minOf { it.x }
        val height = verts.maxOf { it.y } - verts.minOf { it.y }
        assertEquals(0.3, width.toDouble(), 0.01)
        assertEquals(4.0, height.toDouble(), 0.01)
    }

    /** One solid with a broken placement used to blow up the whole scene framing. */
    @Test
    fun `drops solids placed thousands of kilometres away`() {
        val ifc = buildString {
            appendLine("ISO-10303-21;")
            appendLine("DATA;")
            appendLine("#1=IFCCARTESIANPOINT((0.,0.,0.));")
            appendLine("#2=IFCDIRECTION((0.,0.,1.));")
            appendLine("#3=IFCAXIS2PLACEMENT3D(#1,${'$'},${'$'});")
            var id = 10
            listOf(0.0, 4.0, 8.0, 12.0, 9.0e6).forEach { x ->
                val point = id++
                val place = id++
                val local = id++
                val profile = id++
                val solid = id++
                val rep = id++
                val shape = id++
                val wall = id++
                appendLine("#$point=IFCCARTESIANPOINT(($x,0.,0.));")
                appendLine("#$place=IFCAXIS2PLACEMENT3D(#$point,${'$'},${'$'});")
                appendLine("#$local=IFCLOCALPLACEMENT(${'$'},#$place);")
                appendLine("#$profile=IFCRECTANGLEPROFILEDEF(.AREA.,${'$'},${'$'},2.,0.3);")
                appendLine("#$solid=IFCEXTRUDEDAREASOLID(#$profile,#3,#2,2.7);")
                appendLine("#$rep=IFCSHAPEREPRESENTATION(${'$'},'Body','SweptSolid',(#$solid));")
                appendLine("#$shape=IFCPRODUCTDEFINITIONSHAPE(${'$'},${'$'},(#$rep));")
                appendLine("#$wall=IFCWALL('w$x',${'$'},'Muro',${'$'},${'$'},#$local,#$shape,${'$'});")
            }
            appendLine("ENDSEC;")
            appendLine("END-ISO-10303-21;")
        }

        val doc = IfcGeoParser.parse("roto.ifc", ifc)
        val maxAbs = doc.localMeshes.flatMap { it.vertices }.maxOf { abs(it.x) }
        assertTrue("outlier kept: $maxAbs", maxAbs < 1_000f)
        assertTrue("note was ${doc.geometryNote}", doc.geometryNote!!.contains("fuera de rango"))
    }

    /** Round columns and domes are revolved profiles, not extrusions. */
    @Test
    fun `revolves a profile around its axis`() {
        val ifc = """
            ISO-10303-21;
            DATA;
            #1=IFCCARTESIANPOINT((0.,0.,0.));
            #2=IFCDIRECTION((0.,0.,1.));
            #3=IFCAXIS2PLACEMENT3D(#1,${'$'},${'$'});
            #4=IFCCARTESIANPOINT((5.,0.));
            #5=IFCDIRECTION((1.,0.));
            #6=IFCAXIS2PLACEMENT2D(#4,#5);
            #7=IFCRECTANGLEPROFILEDEF(.AREA.,'Anillo',#6,1.,2.);
            #8=IFCAXIS1PLACEMENT(#1,#13);
            #13=IFCDIRECTION((0.,1.,0.));
            #9=IFCREVOLVEDAREASOLID(#7,#3,#8,6.28318530718);
            #10=IFCSHAPEREPRESENTATION(${'$'},'Body','Revolved',(#9));
            #11=IFCPRODUCTDEFINITIONSHAPE(${'$'},${'$'},(#10));
            #12=IFCBUILDINGELEMENTPROXY('p',${'$'},'Cúpula',${'$'},${'$'},${'$'},#11,${'$'},.USERDEFINED.);
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        val doc = IfcGeoParser.parse("revolucion.ifc", ifc)
        val verts = doc.localMeshes.flatMap { it.vertices }
        assertTrue("no geometry", verts.isNotEmpty())

        // Revolving around the model Y axis leaves a ring of radius 4.5–5.5 on the
        // model XZ plane (scene X/Y) and the 2 m profile depth along the axis.
        val radii = verts.map { kotlin.math.sqrt((it.x * it.x + it.y * it.y).toDouble()) }
        assertEquals(5.5, radii.max(), 0.05)
        assertEquals(4.5, radii.min(), 0.05)
        assertEquals(2.0, (verts.maxOf { it.z } - verts.minOf { it.z }).toDouble(), 0.05)
    }

    /** The import error has to name what was in the file, or a failure is unfixable. */
    @Test
    fun `reports the geometry types it could not read`() {
        val ifc = """
            ISO-10303-21;
            DATA;
            #1=IFCCARTESIANPOINT((0.,0.,0.));
            #2=IFCSWEPTDISKSOLID(#10,0.05,${'$'},0.,1.);
            #3=IFCSHAPEREPRESENTATION(${'$'},'Body','SweptDiskSolid',(#2));
            #4=IFCPRODUCTDEFINITIONSHAPE(${'$'},${'$'},(#3));
            #5=IFCFLOWSEGMENT('c',${'$'},'Tubería',${'$'},${'$'},${'$'},#4,${'$'});
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        val message = runCatching { IfcGeoParser.parse("tuberia.ifc", ifc) }
            .exceptionOrNull()?.message.orEmpty()
        assertTrue("message was $message", message.contains("SWEPTDISKSOLID"))
        assertTrue("message was $message", message.contains("1 elementos"))
    }

    /**
     * AR places the mesh by rotating it [KmzDocument.meshRotationDeg] around the up
     * axis and offsetting it to [KmzDocument.meshOrigin]. That has to land exactly
     * on the georeferenced footprint, or the model floats away from the plot.
     */
    @Test
    fun `mesh origin and rotation match the georeferenced footprint`() {
        val rotated = """
            ISO-10303-21;
            DATA;
            #16=IFCPROJECTEDCRS('EPSG:32719','WGS 84 / UTM zone 19S',${'$'},'EPSG','32719',${'$'},#10);
            #17=IFCMAPCONVERSION(#15,#16,229978.386950,8182863.234587,0.000,0.8660254,0.5,1.);
            #40=IFCCARTESIANPOINT((0.,0.));
            #41=IFCCARTESIANPOINT((10.,0.));
            #42=IFCCARTESIANPOINT((10.,6.));
            #43=IFCCARTESIANPOINT((0.,6.));
            #44=IFCPOLYLINE((#40,#41,#42,#43,#40));
            #45=IFCARBITRARYCLOSEDPROFILEDEF(.AREA.,'Footprint',#44);
            #49=IFCEXTRUDEDAREASOLID(#45,${'$'},${'$'},3.000);
            ENDSEC;
            END-ISO-10303-21;
        """.trimIndent()

        val doc = IfcGeoParser.parse("rotado.ifc", rotated)
        val origin = doc.meshOrigin!!
        // 30° from the map conversion plus the UTM grid convergence of the zone.
        assertTrue("rotation was ${doc.meshRotationDeg}", abs(doc.meshRotationDeg - 30f) < 1.5f)

        val theta = Math.toRadians(doc.meshRotationDeg.toDouble())
        val cos = kotlin.math.cos(theta)
        val sin = kotlin.math.sin(theta)
        val vertices = doc.localMeshes.flatMap { it.vertices }

        openRing(doc.polygons.first().ring).forEach { corner ->
            val enu = GeoMath.toEnu(origin, corner)
            val placed = vertices.any { v ->
                val east = v.x * cos + v.z * sin
                val north = -(-v.x * sin + v.z * cos)
                abs(east - enu.east) < 0.05 && abs(north - enu.north) < 0.05
            }
            assertTrue("no mesh vertex at ${enu.east}, ${enu.north}", placed)
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
