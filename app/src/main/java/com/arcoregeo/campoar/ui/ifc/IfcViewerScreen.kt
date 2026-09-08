package com.arcoregeo.campoar.ui.ifc

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.arcoregeo.campoar.data.Vec3f
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.Scene
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.GeometryNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import kotlin.math.max
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IfcViewerScreen(
    document: KmzDocument,
    onBack: () -> Unit,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val materialLoader = rememberMaterialLoader(engine)
    var childNodes by remember { mutableStateOf<List<Node>>(emptyList()) }
    var info by remember { mutableStateOf("Cargando modelo…") }
    var lookTarget by remember { mutableStateOf(Position(0f, 0f, 0f)) }
    var orbitHome by remember { mutableStateOf(Position(0f, 8f, 16f)) }

    val cameraNode = rememberCameraNode(engine) {
        position = orbitHome
        lookAt(lookTarget)
    }
    val cameraManipulator = rememberCameraManipulator(
        orbitHomePosition = orbitHome,
        targetPosition = lookTarget,
    )

    LaunchedEffect(document.id) {
        val built = buildViewerNodes(engine, materialLoader, document.localMeshes)
        childNodes = built.nodes
        info = built.summary.ifBlank {
            if (document.localMeshes.isEmpty()) {
                "Este IFC no tiene malla 3D extraíble."
            } else {
                "${document.localMeshes.size} sólido(s)"
            }
        }
        document.geometryNote?.let { note -> info = "$info · $note" }
        val framing = framingFor(built.spanMeters)
        lookTarget = built.lookAt
        orbitHome = framing.cameraPosition
        cameraNode.near = framing.near
        cameraNode.far = framing.far
        cameraNode.position = framing.cameraPosition
        cameraNode.lookAt(built.lookAt)
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
            if (childNodes.isNotEmpty()) {
                Scene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    materialLoader = materialLoader,
                    cameraNode = cameraNode,
                    cameraManipulator = cameraManipulator,
                    childNodes = childNodes,
                    isOpaque = true,
                )
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
                    .padding(12.dp),
            ) {
                Text(
                    "Visor IFC 3D · arrastra para orbitar / pellizca para zoom",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                )
                Text(
                    buildString {
                        append(info)
                        if (document.isGeoreferenced) append(" · georreferenciado")
                        else append(" · coordenadas locales")
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private data class ViewerBuild(
    val nodes: List<Node>,
    val spanMeters: Float,
    val lookAt: Position,
    val summary: String,
)

private fun buildViewerNodes(
    engine: Engine,
    materialLoader: MaterialLoader,
    meshes: List<LocalMesh>,
): ViewerBuild {
    if (meshes.isEmpty()) {
        return ViewerBuild(emptyList(), 10f, Position(0f, 0f, 0f), "")
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
        return ViewerBuild(emptyList(), 10f, Position(0f, 0f, 0f), "")
    }
    val cx = (minX + maxX) / 2f
    val cy = (minY + maxY) / 2f
    val cz = (minZ + maxZ) / 2f
    val span = max(max(maxX - minX, maxY - minY), maxZ - minZ).coerceAtLeast(1f)

    val colors = listOf(
        Float4(0.98f, 0.55f, 0.18f, 1f),
        Float4(0.25f, 0.75f, 0.95f, 1f),
        Float4(0.45f, 0.85f, 0.45f, 1f),
        Float4(0.95f, 0.75f, 0.25f, 1f),
        Float4(0.85f, 0.45f, 0.85f, 1f),
    )

    val nodes = meshes.mapIndexedNotNull { index, mesh ->
        runCatching {
            meshToNode(
                engine = engine,
                materialLoader = materialLoader,
                mesh = mesh,
                origin = Vec3f(cx, cy, cz),
                color = colors[index % colors.size],
            )
        }.getOrNull()
    }

    return ViewerBuild(
        nodes = nodes,
        spanMeters = span,
        lookAt = Position(0f, 0f, 0f),
        summary = "${meshes.size} sólido(s) · $vertexCount vértices · ${"%.1f".format(span)} m",
    )
}

private fun meshToNode(
    engine: Engine,
    materialLoader: MaterialLoader,
    mesh: LocalMesh,
    origin: Vec3f,
    color: Float4,
): GeometryNode? {
    if (mesh.vertices.isEmpty() || mesh.indices.size < 3) return null

    // Filament reads the index buffer natively: an out-of-range index is a hard crash.
    val safeIndices = ArrayList<Int>(mesh.indices.size)
    val normalsArr = Array(mesh.vertices.size) { Float3(0f, 1f, 0f) }
    var t = 0
    while (t + 2 < mesh.indices.size) {
        val ia = mesh.indices[t]
        val ib = mesh.indices[t + 1]
        val ic = mesh.indices[t + 2]
        if (ia in mesh.vertices.indices && ib in mesh.vertices.indices && ic in mesh.vertices.indices) {
            safeIndices += ia
            safeIndices += ib
            safeIndices += ic
            val a = mesh.vertices[ia]
            val b = mesh.vertices[ib]
            val c = mesh.vertices[ic]
            val e1 = Float3(b.x - a.x, b.y - a.y, b.z - a.z)
            val e2 = Float3(c.x - a.x, c.y - a.y, c.z - a.z)
            val n = cross(e1, e2)
            normalsArr[ia] = add(normalsArr[ia], n)
            normalsArr[ib] = add(normalsArr[ib], n)
            normalsArr[ic] = add(normalsArr[ic], n)
        }
        t += 3
    }
    if (safeIndices.isEmpty()) return null
    val shaded = mesh.vertices.mapIndexed { idx, v ->
        Geometry.Vertex(
            position = Float3(v.x - origin.x, v.y - origin.y, v.z - origin.z),
            normal = normalize(normalsArr[idx]),
            uvCoordinate = Float2(0f, 0f),
            color = color,
        )
    }
    val geometry = Geometry.Builder()
        .vertices(shaded)
        .indices(safeIndices)
        .build(engine)
    val material = materialLoader.createColorInstance(color, metallic = 0.05f, roughness = 0.45f, reflectance = 0.3f)
    return GeometryNode(engine, geometry, material) {
        culling(false)
    }
}

private fun cross(a: Float3, b: Float3): Float3 =
    Float3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    )

private fun add(a: Float3, b: Float3): Float3 = Float3(a.x + b.x, a.y + b.y, a.z + b.z)

private fun normalize(v: Float3): Float3 {
    val len = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
    return if (len < 1e-6f) Float3(0f, 1f, 0f) else Float3(v.x / len, v.y / len, v.z / len)
}
