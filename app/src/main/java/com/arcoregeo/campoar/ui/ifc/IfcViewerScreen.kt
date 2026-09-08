package com.arcoregeo.campoar.ui.ifc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.data.LocalMesh
import com.arcoregeo.campoar.data.MeshEdgeCache
import com.arcoregeo.campoar.data.Vec3f
import com.arcoregeo.campoar.data.shadingOf
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.Scene
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.GeometryNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Palette used when the model is shown as one solid per chunk. */
private val PLAIN_COLORS = listOf(
    Float4(0.98f, 0.55f, 0.18f, 1f),
    Float4(0.25f, 0.75f, 0.95f, 1f),
    Float4(0.45f, 0.85f, 0.45f, 1f),
    Float4(0.95f, 0.75f, 0.25f, 1f),
    Float4(0.85f, 0.45f, 0.85f, 1f),
)

/** Palette used when faces are told apart, on the drawing conventions of a plan. */
private val WALL_COLOR = Float4(0.86f, 0.84f, 0.79f, 1f)
private val TOP_COLOR = Float4(0.96f, 0.70f, 0.32f, 1f)
private val BOTTOM_COLOR = Float4(0.48f, 0.68f, 0.88f, 1f)
private val OUTLINE_COLOR = Float4(0.05f, 0.07f, 0.11f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IfcViewerScreen(
    document: KmzDocument,
    onBack: () -> Unit,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    val edgeCache = remember(document.id) { MeshEdgeCache(document.localMeshes) }
    var model by remember { mutableStateOf<ViewerModel?>(null) }
    var sceneNodes by remember { mutableStateOf<List<Node>>(emptyList()) }
    var edgeMode by remember { mutableStateOf(false) }

    LaunchedEffect(document.id) {
        edgeMode = false
        model = buildViewerModel(engine, materialLoader, document.localMeshes)
    }
    LaunchedEffect(model, edgeMode) {
        val current = model
        if (current == null) {
            sceneNodes = emptyList()
            return@LaunchedEffect
        }
        // Tracing the outline sorts every edge of the building, which is too long to
        // spend on the thread that has to draw the next frame.
        val outlines = if (edgeMode) {
            withContext(Dispatchers.Default) { edgeCache.edges() }
        } else {
            emptyList()
        }
        applyDisplayMode(engine, materialLoader, outlines, current, edgeMode)
        sceneNodes = current.bodies.map { it.node } + current.bodies.mapNotNull { it.edgeNode }
    }

    val ready = model
    val info = buildString {
        append(
            when {
                ready == null -> "Cargando modelo…"
                ready.summary.isNotBlank() -> ready.summary
                document.localMeshes.isEmpty() -> "Este IFC no tiene malla 3D extraíble."
                else -> "${document.localMeshes.size} sólido(s)"
            },
        )
        document.geometryNote?.let { note -> append(" · $note") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document.fileName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF0B1220)),
        ) {
            if (ready != null && sceneNodes.isNotEmpty()) {
                // Keyed on the model so opening another document reframes the camera
                // instead of keeping the previous building's orbit.
                key(ready) {
                    ModelScene(
                        engine = engine,
                        modelLoader = modelLoader,
                        materialLoader = materialLoader,
                        model = ready,
                        nodes = sceneNodes,
                    )
                }
            } else {
                Text(
                    info,
                    color = Color(0xFFF87171),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC0F172A))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Visor IFC 3D · arrastra para orbitar",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = { edgeMode = !edgeMode },
                        enabled = ready != null && ready.bodies.isNotEmpty(),
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (edgeMode) Color(0xFFEA580C) else Color(0xFF334155),
                        ),
                    ) {
                        Text(
                            if (edgeMode) "Aristas ON" else "Ver aristas / colores",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    buildString {
                        append(info)
                        if (document.isGeoreferenced) append(" · georreferenciado")
                        else append(" · coordenadas locales")
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * The camera manipulator is built on its first composition and never again, and from
 * the first touch onwards it owns the camera transform: whatever orbit home it was
 * given is where the camera snaps back to. Composing the scene only once the model
 * size is known is what keeps a large building on screen after the first drag.
 */
@Composable
private fun ModelScene(
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    model: ViewerModel,
    nodes: List<Node>,
) {
    val framing = remember(model.spanMeters) { framingFor(model.spanMeters) }
    val cameraNode = rememberCameraNode(engine) {
        near = framing.near
        far = framing.far
        position = framing.cameraPosition
        lookAt(model.lookAt)
    }
    Scene(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        cameraNode = cameraNode,
        cameraManipulator = rememberCameraManipulator(
            orbitHomePosition = framing.cameraPosition,
            targetPosition = model.lookAt,
        ),
        childNodes = nodes,
        isOpaque = true,
    )
}

/** One renderable chunk of the model, with the materials of both display modes. */
private class ViewerBody(
    val meshIndex: Int,
    val mesh: LocalMesh,
    val origin: Vec3f,
    val node: GeometryNode,
    val plain: List<MaterialInstance>,
    val byFacing: List<MaterialInstance>,
) {
    var edgeNode: GeometryNode? = null
}

private class ViewerModel(
    val bodies: List<ViewerBody>,
    val spanMeters: Float,
    val lookAt: Position,
    val summary: String,
)

/**
 * Swaps the two ways of showing the model. An outline is turned into buffers the
 * first time it is asked for and only hidden afterwards, so the button stays instant
 * and a model nobody outlines never pays for it.
 */
private fun applyDisplayMode(
    engine: Engine,
    materialLoader: MaterialLoader,
    outlines: List<List<Int>>,
    model: ViewerModel,
    edges: Boolean,
) {
    model.bodies.forEach { body ->
        val materials = if (edges) body.byFacing else body.plain
        materials.forEachIndexed { index, material ->
            runCatching { body.node.setMaterialInstanceAt(index, material) }
        }
        if (edges && body.edgeNode == null) {
            body.edgeNode = runCatching {
                outlineNode(
                    engine = engine,
                    materialLoader = materialLoader,
                    body = body,
                    edges = outlines.getOrNull(body.meshIndex).orEmpty(),
                )
            }.getOrNull()
        }
        body.edgeNode?.isVisible = edges
    }
}

private fun buildViewerModel(
    engine: Engine,
    materialLoader: MaterialLoader,
    meshes: List<LocalMesh>,
): ViewerModel {
    if (meshes.isEmpty()) {
        return ViewerModel(emptyList(), 10f, Position(0f, 0f, 0f), "")
    }

    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var minZ = Float.MAX_VALUE
    var maxZ = -Float.MAX_VALUE
    var vertexCount = 0
    meshes.forEach { mesh ->
        mesh.vertices.forEach { v ->
            // A single NaN would make the bounds NaN and leave the camera nowhere.
            if (!v.x.isFinite() || !v.y.isFinite() || !v.z.isFinite()) return@forEach
            if (v.x < minX) minX = v.x
            if (v.x > maxX) maxX = v.x
            if (v.y < minY) minY = v.y
            if (v.y > maxY) maxY = v.y
            if (v.z < minZ) minZ = v.z
            if (v.z > maxZ) maxZ = v.z
            vertexCount++
        }
    }
    if (vertexCount == 0) {
        return ViewerModel(emptyList(), 10f, Position(0f, 0f, 0f), "")
    }
    val cx = (minX + maxX) / 2f
    val cy = (minY + maxY) / 2f
    val cz = (minZ + maxZ) / 2f
    val span = max(max(maxX - minX, maxY - minY), maxZ - minZ).coerceAtLeast(1f)

    val bodies = meshes.mapIndexedNotNull { index, mesh ->
        runCatching {
            meshToBody(
                engine = engine,
                materialLoader = materialLoader,
                meshIndex = index,
                mesh = mesh,
                origin = Vec3f(cx, cy, cz),
                color = PLAIN_COLORS[index % PLAIN_COLORS.size],
            )
        }.getOrNull()
    }

    return ViewerModel(
        bodies = bodies,
        spanMeters = span,
        lookAt = Position(0f, 0f, 0f),
        summary = "${meshes.size} sólido(s) · $vertexCount vértices · ${"%.1f".format(span)} m",
    )
}

/**
 * Builds the chunk as one renderable per group of faces, so switching to the second
 * palette is a material swap rather than a rebuild of the whole model.
 */
private fun meshToBody(
    engine: Engine,
    materialLoader: MaterialLoader,
    meshIndex: Int,
    mesh: LocalMesh,
    origin: Vec3f,
    color: Float4,
): ViewerBody? {
    if (mesh.vertices.isEmpty() || mesh.indices.size < 3) return null

    val shading = shadingOf(mesh)
    // Filament reads the index buffer natively: an out-of-range index is a hard
    // crash, so only the triangles that came back checked are drawn.
    val groups = listOf(
        shading.walls to WALL_COLOR,
        shading.tops to TOP_COLOR,
        shading.bottoms to BOTTOM_COLOR,
    ).filter { (indices, _) -> indices.isNotEmpty() }
    if (groups.isEmpty()) return null

    val vertices = mesh.vertices.mapIndexed { index, v ->
        Geometry.Vertex(
            position = Float3(v.x - origin.x, v.y - origin.y, v.z - origin.z),
            normal = Float3(
                shading.normals[index * 3],
                shading.normals[index * 3 + 1],
                shading.normals[index * 3 + 2],
            ),
            uvCoordinate = Float2(0f, 0f),
            color = color,
        )
    }
    val geometry = Geometry.Builder()
        .vertices(vertices)
        .primitivesIndices(groups.map { (indices, _) -> indices })
        .build(engine)

    val single = materialLoader.createColorInstance(
        color,
        metallic = 0.05f,
        roughness = 0.45f,
        reflectance = 0.3f,
    )
    val plain = groups.map { single }
    val byFacing = groups.map { (_, tone) ->
        materialLoader.createColorInstance(
            tone,
            metallic = 0f,
            roughness = 0.7f,
            reflectance = 0.1f,
        ).apply {
            // The outline runs exactly along the surface, so the faces are nudged
            // back a hair to keep the lines from being eaten by the depth test.
            setPolygonOffset(2f, 2f)
        }
    }
    val node = GeometryNode(engine = engine, geometry = geometry, materialInstances = plain) {
        culling(false)
    }
    return ViewerBody(meshIndex, mesh, origin, node, plain, byFacing)
}

private fun outlineNode(
    engine: Engine,
    materialLoader: MaterialLoader,
    body: ViewerBody,
    edges: List<Int>,
): GeometryNode? {
    if (edges.size < 2) return null
    val vertices = body.mesh.vertices.map { v ->
        Geometry.Vertex(
            position = Float3(v.x - body.origin.x, v.y - body.origin.y, v.z - body.origin.z),
            normal = Float3(0f, 1f, 0f),
            uvCoordinate = Float2(0f, 0f),
            color = OUTLINE_COLOR,
        )
    }
    val geometry = Geometry.Builder(RenderableManager.PrimitiveType.LINES)
        .vertices(vertices)
        .indices(edges)
        .build(engine)
    val material = materialLoader.createColorInstance(
        OUTLINE_COLOR,
        metallic = 0f,
        roughness = 1f,
        reflectance = 0f,
    )
    return GeometryNode(engine, geometry, material) {
        culling(false)
    }
}
