package com.arcoregeo.campoar.ui.ar

import android.Manifest
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arcoregeo.campoar.BuildConfig
import com.arcoregeo.campoar.data.GeoPoint
import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.data.LatLngAlt
import com.arcoregeo.campoar.data.centroidOf
import com.arcoregeo.campoar.data.openRing
import com.arcoregeo.campoar.geo.DevicePose
import com.arcoregeo.campoar.geo.GeoMath
import com.arcoregeo.campoar.geo.ReferenceCalibration
import com.arcoregeo.campoar.viewmodel.CampoUiState
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Earth
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberOnGestureListener
import kotlin.math.atan2

private enum class CalibMode { Idle, WaitTap1, WaitTap2 }

private const val SOLID_HEIGHT_M = 3f
private const val EYE_HEIGHT_M = 1.4f

/** How the virtual content is currently anchored to the real world. */
enum class Placement { None, Gps, Geospatial, Manual, Local }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArScreen(
    document: KmzDocument,
    state: CampoUiState,
    onBack: () -> Unit,
    onStartLocation: () -> Unit,
    onStopLocation: () -> Unit,
    onSelectPoint: (String) -> Unit,
    onGeospatialStatus: (Boolean, Double?, String?) -> Unit,
    onCalibrationChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasArPermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionGranted = result.values.all { it }
        if (permissionGranted) onStartLocation()
    }

    val targets = remember(document.id) { document.arTargets() }
    var calibMode by remember { mutableStateOf(CalibMode.Idle) }
    var calibration by remember { mutableStateOf<ReferenceCalibration?>(null) }
    var rootAnchor by remember { mutableStateOf<Anchor?>(null) }
    var ref1Geo by remember { mutableStateOf<LatLngAlt?>(null) }
    var ref1World by remember { mutableStateOf<FloatArray?>(null) }
    var showSolid by remember { mutableStateOf(document.polygons.isNotEmpty()) }
    var showMarkers by remember { mutableStateOf(document.polygons.isEmpty()) }
    var sceneView by remember { mutableStateOf<ARSceneView?>(null) }
    var calibPanelOpen by remember { mutableStateOf(false) }
    var fixHint by remember { mutableStateOf<String?>(null) }
    var uiHidden by remember { mutableStateOf(false) }
    var placement by remember { mutableStateOf(Placement.None) }
    var bringHereTick by remember { mutableStateOf(0) }
    var resumeGpsTick by remember { mutableStateOf(0) }
    var solidParts by remember { mutableStateOf(0) }
    var gpsLocked by remember { mutableStateOf(false) }
    var heightOffsetM by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        if (permissionGranted) onStartLocation()
        else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }
    LaunchedEffect(targets, state.selectedPointId) {
        if (state.selectedPointId == null || targets.none { it.id == state.selectedPointId }) {
            targets.firstOrNull()?.id?.let(onSelectPoint)
        }
    }
    LaunchedEffect(calibration) {
        onCalibrationChanged(calibration?.refCount ?: 0)
    }
    DisposableEffect(Unit) {
        onDispose { onStopLocation() }
    }

    val selected = targets.firstOrNull { it.id == state.selectedPointId } ?: targets.firstOrNull()
    val pose = state.pose
    val solidCentroid = remember(document.id) {
        val allVerts = document.polygons.flatMap { openRing(it.ring) }
        centroidOf(allVerts) ?: targets.firstOrNull()?.coordinate
    }
    val solidHalfExtentM = remember(document.id, solidCentroid) {
        val c = solidCentroid ?: return@remember 15f
        var maxD = 0.0
        document.polygons.forEach { poly ->
            openRing(poly.ring).forEach { p ->
                maxD = maxOf(maxD, GeoMath.distanceMeters(c, p))
            }
        }
        maxD.toFloat().coerceAtLeast(5f)
    }

    fun clearCalibration() {
        rootAnchor?.detach()
        rootAnchor = null
        calibration = null
        ref1Geo = null
        ref1World = null
        calibMode = CalibMode.Idle
    }

    fun handlePlaneTap(view: ARSceneView, x: Float, y: Float) {
        if (calibMode == CalibMode.Idle) return
        val selectedPoint = selected ?: return
        val hit = view.hitTestAR(
            xPx = x,
            yPx = y,
            planeTypes = setOf(com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING),
        ) ?: return

        val hitPose = hit.hitPose
        val world = floatArrayOf(hitPose.tx(), hitPose.ty(), hitPose.tz())
        // Keep the anchor axis-aligned with the session frame so the yaw maths below stay valid.
        val anchor = runCatching {
            hit.trackable.createAnchor(Pose.makeTranslation(world[0], world[1], world[2]))
        }.getOrNull() ?: return

        when (calibMode) {
            CalibMode.WaitTap1 -> {
                rootAnchor?.detach()
                rootAnchor = anchor
                ref1Geo = selectedPoint.coordinate
                ref1World = world
                calibration = ReferenceCalibration.fromControls(selectedPoint.coordinate)
                calibMode = CalibMode.Idle
            }
            CalibMode.WaitTap2 -> {
                val g1 = ref1Geo
                val w1 = ref1World
                if (g1 == null || w1 == null) {
                    anchor.detach()
                    calibMode = CalibMode.WaitTap1
                    return
                }
                val dx = world[0] - w1[0]
                val dz = world[2] - w1[2]
                val worldBearing = (Math.toDegrees(atan2(dx.toDouble(), (-dz).toDouble())) + 360.0) % 360.0
                calibration = ReferenceCalibration.fromControls(
                    geo1 = g1,
                    geo2 = selectedPoint.coordinate,
                    worldBearing1to2Degrees = worldBearing,
                )
                anchor.detach()
                calibMode = CalibMode.Idle
            }
            CalibMode.Idle -> Unit
        }
    }

    Scaffold(
        topBar = {
            if (!uiHidden) {
                TopAppBar(
                    title = { Text("AR en campo") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (permissionGranted) {
                ArWorldScene(
                    targets = targets,
                    document = document,
                    solidCentroid = solidCentroid,
                    solidHalfExtentM = solidHalfExtentM,
                    showSolid = showSolid,
                    showMarkers = showMarkers,
                    calibration = calibration,
                    rootAnchor = rootAnchor,
                    pose = pose,
                    bringHereTick = bringHereTick,
                    resumeGpsTick = resumeGpsTick,
                    gpsLocked = gpsLocked,
                    heightOffsetM = heightOffsetM,
                    onGeospatialStatus = onGeospatialStatus,
                    onPlacementChanged = { placement = it },
                    onSolidBuilt = { solidParts = it },
                    onSceneViewReady = { sceneView = it },
                    onPlaneTap = { view, x, y -> handlePlaneTap(view, x, y) },
                    calibrateActive = calibMode != CalibMode.Idle,
                )
            }

            if (!uiHidden) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    StatusChip(
                        placement = placement,
                        accuracy = state.geospatialHorizontalAccuracy ?: pose?.accuracyMeters?.toDouble(),
                        hasApiKey = BuildConfig.HAS_ARCORE_API_KEY,
                        calibRefs = calibration?.refCount ?: 0,
                        calibrating = calibMode != CalibMode.Idle,
                        solidParts = solidParts,
                        showSolid = showSolid,
                        polygonCount = document.polygons.size,
                    )
                    ControlBar(
                        refCount = calibration?.refCount ?: 0,
                        panelOpen = calibPanelOpen || calibMode != CalibMode.Idle,
                        showSolid = showSolid,
                        showMarkers = showMarkers,
                        gpsLocked = gpsLocked,
                        heightOffsetM = heightOffsetM,
                        placement = placement,
                        onTogglePanel = {
                            calibPanelOpen = !calibPanelOpen
                            if (!calibPanelOpen) calibMode = CalibMode.Idle
                        },
                        onToggleSolid = { showSolid = !showSolid },
                        onToggleMarkers = { showMarkers = !showMarkers },
                        onBringHere = {
                            bringHereTick += 1
                            gpsLocked = false
                            fixHint = "Demo: sólido a ~25–30 m delante (no es la posición GPS real)"
                        },
                        onToggleGpsLock = {
                            if (placement == Placement.Local) {
                                // Exit demo placement and resume real GPS/Geospatial distance.
                                resumeGpsTick += 1
                                gpsLocked = false
                                fixHint = "GPS real: lejos se ve pequeño, cerca se ve grande"
                            } else {
                                gpsLocked = !gpsLocked
                                fixHint = if (gpsLocked) {
                                    "GPS anclado · el sólido ya no salta con el GPS"
                                } else {
                                    "GPS libre · lejos=pequeño, cerca=grande"
                                }
                            }
                        },
                        onHeightUp = {
                            heightOffsetM = (heightOffsetM + 0.25f).coerceAtMost(8f)
                        },
                        onHeightDown = {
                            heightOffsetM = (heightOffsetM - 0.25f).coerceAtLeast(-4f)
                        },
                        onHeightReset = { heightOffsetM = 0f },
                        onHideUi = {
                            uiHidden = true
                            calibMode = CalibMode.Idle
                        },
                    )
                    if (calibPanelOpen || calibMode != CalibMode.Idle) {
                        CalibrationHelp(
                            selectedName = selected?.name,
                            calibMode = calibMode,
                            refCount = calibration?.refCount ?: 0,
                            canStart = selected != null,
                            canRef2 = selected != null && calibration != null,
                            onStartRef1 = {
                                if (selected != null) {
                                    calibMode = CalibMode.WaitTap1
                                    fixHint = null
                                }
                            },
                            onStartRef2 = {
                                if (selected != null && calibration != null) {
                                    calibMode = CalibMode.WaitTap2
                                    fixHint = null
                                }
                            },
                            onClear = {
                                clearCalibration()
                                fixHint = null
                            },
                            onHide = {
                                calibPanelOpen = false
                                calibMode = CalibMode.Idle
                            },
                        )
                    }
                    fixHint?.let { msg ->
                        Text(
                            msg,
                            color = Color(0xFF86EFAC),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .background(Color(0xCC14532D), RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (targets.isEmpty()) {
                        Text(
                            "No hay coordenadas para AR. Reimporta el KMZ/KML.",
                            color = Color(0xFFF87171),
                            modifier = Modifier
                                .background(Color(0xCC0F172A), MaterialTheme.shapes.medium)
                                .padding(12.dp),
                        )
                    } else if (pose != null && solidCentroid != null && calibMode == CalibMode.Idle) {
                        GpsRelativeHud(
                            pose = pose,
                            solidCentroid = solidCentroid,
                            placement = placement,
                            gpsLocked = gpsLocked,
                        )
                    }
                }
            }

            if (calibMode != CalibMode.Idle) {
                AimReticle(
                    modifier = Modifier.align(Alignment.Center),
                    label = if (calibMode == CalibMode.WaitTap1) {
                        "Apunta al piso · vértice ${selected?.name ?: ""}"
                    } else {
                        "Apunta al 2.º punto en el piso"
                    },
                )
                Button(
                    onClick = {
                        val view = sceneView ?: return@Button
                        val before = calibration?.refCount ?: 0
                        handlePlaneTap(view, view.width / 2f, view.height / 2f)
                        val after = calibration?.refCount ?: 0
                        if (after > before) {
                            fixHint = if (after >= 2) {
                                "✓ Anclado al piso (listo)"
                            } else {
                                "✓ Ref 1 fijada · puedes ocultar el panel"
                            }
                            calibPanelOpen = after < 2
                        } else {
                            fixHint = "No se detectó el piso. Baja la mira y espera el plano gris."
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 88.dp)
                        .fillMaxWidth(0.85f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("FIJAR AQUÍ", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }

            if (!uiHidden) {
                if (pose != null && selected != null && calibMode == CalibMode.Idle) {
                    CompassNeedle(
                        heading = pose.headingDegrees,
                        targetBearing = GeoMath.bearingDegrees(pose.coordinate, selected.coordinate).toFloat(),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(16.dp)
                            .size(72.dp),
                    )
                }
                OverlayGeometry(
                    document = document,
                    targets = targets,
                    pose = pose,
                    selectedId = selected?.id,
                )
                LazyRow(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xCC0F172A))
                        .padding(12.dp),
                ) {
                    items(targets, key = { it.id }) { point ->
                        val distance = pose?.let { GeoMath.distanceMeters(it.coordinate, point.coordinate) }
                        FilterChip(
                            selected = point.id == selected?.id,
                            onClick = { onSelectPoint(point.id) },
                            label = {
                                Text(
                                    buildString {
                                        append(point.name)
                                        if (distance != null) {
                                            append(" · ")
                                            append(GeoMath.formatDistance(distance))
                                        }
                                    },
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            } else {
                OutlinedButton(
                    onClick = { uiHidden = false },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .height(44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x99000000)),
                ) {
                    Text("Mostrar menú", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ArWorldScene(
    targets: List<GeoPoint>,
    document: KmzDocument,
    solidCentroid: LatLngAlt?,
    solidHalfExtentM: Float,
    showSolid: Boolean,
    showMarkers: Boolean,
    calibration: ReferenceCalibration?,
    rootAnchor: Anchor?,
    pose: DevicePose?,
    bringHereTick: Int,
    resumeGpsTick: Int,
    gpsLocked: Boolean,
    heightOffsetM: Float,
    onGeospatialStatus: (Boolean, Double?, String?) -> Unit,
    onPlacementChanged: (Placement) -> Unit,
    onSolidBuilt: (Int) -> Unit,
    onSceneViewReady: (ARSceneView) -> Unit,
    onPlaneTap: (ARSceneView, Float, Float) -> Unit,
    calibrateActive: Boolean,
) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val modelLoader = rememberModelLoader(engine)
    var childNodes by remember { mutableStateOf(emptyList<Node>()) }
    var sceneViewRef by remember { mutableStateOf<ARSceneView?>(null) }

    var activeAnchor by remember { mutableStateOf<Anchor?>(null) }
    var activeCalib by remember { mutableStateOf<ReferenceCalibration?>(null) }
    var placement by remember { mutableStateOf(Placement.None) }
    var handledBringHere by remember { mutableStateOf(0) }
    var handledResumeGps by remember { mutableStateOf(0) }

    // The AR session callback outlives recompositions, so read the live values.
    val livePose by rememberUpdatedState(pose)
    val liveCentroid by rememberUpdatedState(solidCentroid)
    val liveHalfExtent by rememberUpdatedState(solidHalfExtentM)
    val liveBringHere by rememberUpdatedState(bringHereTick)
    val liveResumeGps by rememberUpdatedState(resumeGpsTick)
    val livePlacement by rememberUpdatedState(placement)
    val liveCalib by rememberUpdatedState(activeCalib)
    val liveGpsLocked by rememberUpdatedState(gpsLocked)

    fun replaceAnchor(anchor: Anchor, calib: ReferenceCalibration, mode: Placement) {
        // Manual anchors belong to ArScreen, which detaches them on "Quitar ancla".
        if (placement != Placement.Manual) activeAnchor?.detach()
        activeAnchor = anchor
        activeCalib = calib
        placement = mode
        onPlacementChanged(mode)
    }

    // Manual plane calibration always wins.
    LaunchedEffect(calibration, rootAnchor) {
        if (calibration != null && rootAnchor != null) {
            if (placement != Placement.Manual) activeAnchor?.detach()
            activeAnchor = rootAnchor
            activeCalib = calibration
            placement = Placement.Manual
            onPlacementChanged(Placement.Manual)
        } else if (placement == Placement.Manual) {
            activeAnchor = null
            activeCalib = null
            placement = Placement.None
            onPlacementChanged(Placement.None)
        }
    }

    LaunchedEffect(activeAnchor, activeCalib, targets, document.id, showSolid, showMarkers, pose?.coordinate, heightOffsetM) {
        val anchor = activeAnchor
        val calib = activeCalib
        if (anchor == null || calib == null) {
            childNodes = emptyList()
            onSolidBuilt(0)
            return@LaunchedEffect
        }
        val root = AnchorNode(engine = engine, anchor = anchor)
        var parts = 0
        if (showSolid) {
            document.polygons.forEach { polygon ->
                val solid = buildSolidNodes(
                    engine = engine,
                    materialLoader = materialLoader,
                    ring = openRing(polygon.ring),
                    calibration = calib,
                    heightMeters = document.solidHeightMeters ?: SOLID_HEIGHT_M,
                    heightOffsetMeters = heightOffsetM,
                )
                parts += solid.size
                solid.forEach { root.addChildNode(it) }
            }
        }
        onSolidBuilt(parts)
        if (showMarkers) {
            targets.forEach { point ->
                val enu = calib.enuOf(point.coordinate)
                root.addChildNode(
                    CubeNode(
                        engine = engine,
                        size = Size(0.22f, 1.6f, 0.22f),
                        center = Position(
                            x = enu.east.toFloat(),
                            y = 0.8f + enu.up.toFloat(),
                            z = (-enu.north).toFloat(),
                        ),
                        materialInstance = materialLoader.createArVisibleColor(
                            android.graphics.Color.parseColor("#38BDF8"),
                        ),
                    ),
                )
            }
        }
        // Green "TÚ" marker at the real GPS position relative to the solid frame.
        // In GPS mode (origin = phone) this sits at the camera; in Geospatial/Manual
        // it sits wherever GPS says you are vs the plot — so you can see if you're
        // outside the solid instead of assuming the center point is you.
        pose?.coordinate?.let { you ->
            val enu = calib.enuOf(you)
            val dist = kotlin.math.sqrt(enu.east * enu.east + enu.north * enu.north)
            if (dist < 250.0) {
                root.addChildNode(
                    CubeNode(
                        engine = engine,
                        size = Size(0.45f, 2.0f, 0.45f),
                        center = Position(
                            x = enu.east.toFloat(),
                            y = 1.0f + enu.up.toFloat(),
                            z = (-enu.north).toFloat(),
                        ),
                        materialInstance = materialLoader.createArVisibleColor(
                            android.graphics.Color.parseColor("#22C55E"),
                        ),
                    ),
                )
            }
        }
        childNodes = listOf(root)
    }

    val gestureListener = rememberOnGestureListener(
        onSingleTapConfirmed = { e: MotionEvent, _ ->
            val view = sceneViewRef
            if (view != null && calibrateActive) {
                onPlaneTap(view, e.x, e.y)
                true
            } else {
                false
            }
        },
    )

    ARScene(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        modelLoader = modelLoader,
        materialLoader = materialLoader,
        planeRenderer = calibrateActive,
        childNodes = childNodes,
        onGestureListener = gestureListener,
        sessionConfiguration = { session, config ->
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
            if (session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                config.geospatialMode = Config.GeospatialMode.ENABLED
            }
        },
        onViewCreated = {
            sceneViewRef = this
            onSceneViewReady(this)
        },
        onSessionUpdated = { session, frame ->
            val earth = session.earth
            val geoPose = earth?.cameraGeospatialPose
            val tracking = earth?.trackingState == TrackingState.TRACKING &&
                earth.earthState == Earth.EarthState.ENABLED
            val geoAccuracy = geoPose?.horizontalAccuracy
            onGeospatialStatus(tracking, geoAccuracy, earth?.earthState?.name)

            val camera = frame.camera
            val cameraReady = camera.trackingState == TrackingState.TRACKING

            val centroid = liveCentroid
            val devicePose = livePose

            if (liveResumeGps != handledResumeGps) {
                handledResumeGps = liveResumeGps
                if (livePlacement == Placement.Local) {
                    activeAnchor?.detach()
                    activeAnchor = null
                    activeCalib = null
                    placement = Placement.None
                    onPlacementChanged(Placement.None)
                }
            }

            if (liveBringHere != handledBringHere && cameraReady && centroid != null) {
                handledBringHere = liveBringHere
                val camPose = camera.pose
                val forward = camPose.zAxis
                // Stand back so the full solid fits: ~25–30 m for small plots,
                // further for large footprints (half-extent + margin).
                val viewDistance = maxOf(25f, liveHalfExtent * 1.6f + 10f).coerceIn(25f, 70f)
                val front = Pose.makeTranslation(
                    camPose.tx() - forward[0] * viewDistance,
                    camPose.ty() - EYE_HEIGHT_M,
                    camPose.tz() - forward[2] * viewDistance,
                )
                runCatching { session.createAnchor(front) }.getOrNull()?.let { anchor ->
                    replaceAnchor(
                        anchor,
                        ReferenceCalibration(originGeo = centroid, yawDegrees = 0.0, refCount = 0),
                        Placement.Local,
                    )
                }
            }

            if (livePlacement == Placement.Manual || livePlacement == Placement.Local) return@ARScene
            // Frozen GPS pose: do not chase new fixes (stops the solid from jumping).
            if (liveGpsLocked && livePlacement == Placement.Gps) return@ARScene

            // Geospatial only when we actually have a usable Earth pose. Never
            // block the GPS fallback if createAnchor fails.
            val geospatialOk = tracking &&
                earth != null &&
                centroid != null &&
                geoAccuracy != null &&
                geoAccuracy <= 25.0 &&
                !liveGpsLocked
            if (geospatialOk && livePlacement != Placement.Geospatial) {
                val altitude = (geoPose?.altitude ?: 0.0) - EYE_HEIGHT_M
                val anchor = runCatching {
                    earth!!.createAnchor(
                        centroid!!.latitude,
                        centroid.longitude,
                        altitude,
                        0f, 0f, 0f, 1f,
                    )
                }.getOrNull()
                if (anchor != null) {
                    replaceAnchor(
                        anchor,
                        ReferenceCalibration(originGeo = centroid, yawDegrees = 0.0, refCount = 0),
                        Placement.Geospatial,
                    )
                    return@ARScene
                }
            }
            if (livePlacement == Placement.Geospatial) return@ARScene

            // GPS + compass: solid stays at its real lat/lon relative to YOUR GPS.
            if (cameraReady && devicePose != null && devicePose.hasHeading) {
                val camPose = camera.pose
                val forward = camPose.zAxis
                val forwardAngle = Math.toDegrees(
                    atan2(-forward[0].toDouble(), forward[2].toDouble()),
                )
                val yaw = devicePose.headingDegrees - forwardAngle
                val newCalib = ReferenceCalibration(
                    originGeo = devicePose.coordinate,
                    yawDegrees = yaw,
                    refCount = 0,
                )
                if (livePlacement != Placement.Gps) {
                    val groundPose = Pose.makeTranslation(
                        camPose.tx(),
                        camPose.ty() - EYE_HEIGHT_M,
                        camPose.tz(),
                    )
                    runCatching { session.createAnchor(groundPose) }.getOrNull()?.let { anchor ->
                        replaceAnchor(anchor, newCalib, Placement.Gps)
                    }
                } else if (!liveGpsLocked) {
                    val prev = liveCalib
                    if (prev == null ||
                        GeoMath.distanceMeters(prev.originGeo, devicePose.coordinate) > 1.5 ||
                        kotlin.math.abs(GeoMath.wrappedDeltaDegrees(prev.yawDegrees, yaw)) > 8f
                    ) {
                        activeCalib = newCalib
                    }
                }
            }
        },
    )
}

@Composable
private fun ControlBar(
    refCount: Int,
    panelOpen: Boolean,
    showSolid: Boolean,
    showMarkers: Boolean,
    gpsLocked: Boolean,
    heightOffsetM: Float,
    placement: Placement,
    onTogglePanel: () -> Unit,
    onToggleSolid: () -> Unit,
    onToggleMarkers: () -> Unit,
    onBringHere: () -> Unit,
    onToggleGpsLock: () -> Unit,
    onHeightUp: () -> Unit,
    onHeightDown: () -> Unit,
    onHeightReset: () -> Unit,
    onHideUi: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    gpsLocked -> "GPS fijo"
                    refCount >= 2 -> "✓ Piso"
                    refCount == 1 -> "1/2 piso"
                    else -> "Libre"
                },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(
                        when {
                            gpsLocked -> Color(0xFF2563EB)
                            refCount >= 2 -> Color(0xFF16A34A)
                            refCount == 1 -> Color(0xFFCA8A04)
                            else -> Color(0xFF64748B)
                        },
                        RoundedCornerShape(20.dp),
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Button(
                onClick = onToggleGpsLock,
                enabled = placement != Placement.None || gpsLocked,
                modifier = Modifier.height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (gpsLocked) Color(0xFFDC2626) else Color(0xFF2563EB),
                ),
            ) {
                Text(
                    if (gpsLocked) "Desanclar GPS" else "Anclar GPS",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(onClick = onBringHere, modifier = Modifier.height(40.dp)) {
                Text("Traer aquí", color = Color.White, fontSize = 12.sp)
            }
            if (placement == Placement.Local) {
                OutlinedButton(
                    onClick = onToggleGpsLock,
                    modifier = Modifier.height(40.dp),
                ) {
                    Text("GPS real", color = Color(0xFF86EFAC), fontSize = 12.sp)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Altura", color = Color.White, fontSize = 12.sp)
            Button(
                onClick = onHeightDown,
                modifier = Modifier.size(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("↓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            Text(
                String.format("%+.2f m", heightOffsetM),
                color = Color(0xFFFBBF24),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(Color(0xCC0F172A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Button(
                onClick = onHeightUp,
                modifier = Modifier.size(44.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("↑", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = onHeightReset, modifier = Modifier.height(40.dp)) {
                Text("0", color = Color.White, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onTogglePanel, modifier = Modifier.height(40.dp)) {
                Text(if (panelOpen) "Piso…" else "Piso", color = Color.White, fontSize = 12.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onToggleSolid, modifier = Modifier.weight(1f).height(40.dp)) {
                Text(if (showSolid) "Sólido ON" else "Sólido OFF", color = Color.White, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onToggleMarkers, modifier = Modifier.weight(1f).height(40.dp)) {
                Text(if (showMarkers) "Marcas ON" else "Marcas OFF", color = Color.White, fontSize = 12.sp)
            }
            Button(
                onClick = onHideUi,
                modifier = Modifier.weight(1f).height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
            ) {
                Text("Ocultar", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CalibrationHelp(
    selectedName: String?,
    calibMode: CalibMode,
    refCount: Int,
    canStart: Boolean,
    canRef2: Boolean,
    onStartRef1: () -> Unit,
    onStartRef2: () -> Unit,
    onClear: () -> Unit,
    onHide: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Color(0xCC0F172A), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("¿Para qué sirve?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "El GPS solo aproxima. Anclar = decirle a la app dónde está un vértice del terreno en el piso real, para que el sólido no flote.",
            color = Color(0xFFCBD5E1),
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Text(
            when (calibMode) {
                CalibMode.WaitTap1 ->
                    "Ahora: elige abajo «${selectedName ?: "vértice"}», apunta la MIRA al piso donde está ese punto, pulsa FIJAR AQUÍ."
                CalibMode.WaitTap2 ->
                    "Ahora: elige otro vértice abajo, apunta la mira a su lugar en el piso, pulsa FIJAR AQUÍ."
                CalibMode.Idle -> when {
                    refCount >= 2 -> "Listo. Pulsa Ocultar para ver la cámara libre."
                    refCount == 1 -> "Ref 1 OK. Opcional: Anclar 2.º para orientar mejor."
                    else -> "1) Elige un vértice abajo  2) Pulsa Anclar 1  3) Apunta la mira al piso  4) FIJAR AQUÍ"
                }
            },
            color = if (calibMode != CalibMode.Idle) Color(0xFFFBBF24) else Color(0xFFE2E8F0),
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
        if (calibMode == CalibMode.Idle) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onStartRef1,
                    enabled = canStart,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                ) { Text("Anclar 1", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = onStartRef2,
                    enabled = canRef2,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                ) { Text("Anclar 2", fontWeight = FontWeight.Bold) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                    Text("Quitar ancla", color = Color.White)
                }
                OutlinedButton(onClick = onHide, modifier = Modifier.weight(1f)) {
                    Text("Ocultar panel", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun AimReticle(modifier: Modifier = Modifier, label: String) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(Color(0xAA0F172A), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Canvas(modifier = Modifier.size(72.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 4f
            drawCircle(Color(0xFFF97316), radius = r, center = c, style = Stroke(width = 4f))
            drawLine(Color(0xFFF97316), Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = 3f)
            drawLine(Color(0xFFF97316), Offset(c.x, c.y - r), Offset(c.x, c.y + r), strokeWidth = 3f)
            drawCircle(Color.White, radius = 5f, center = c)
        }
    }
}

@Composable
private fun GpsRelativeHud(
    pose: DevicePose,
    solidCentroid: LatLngAlt,
    placement: Placement,
    gpsLocked: Boolean,
) {
    val distance = GeoMath.distanceMeters(pose.coordinate, solidCentroid)
    val bearing = GeoMath.bearingDegrees(pose.coordinate, solidCentroid)
    val headingHint = if (pose.hasHeading) {
        val delta = GeoMath.wrappedDeltaDegrees(bearing, pose.headingDegrees.toDouble())
        when {
            kotlin.math.abs(delta) < 25f -> "delante"
            delta > 0 -> "a la derecha (${delta.toInt()}°)"
            else -> "a la izquierda (${-delta.toInt()}°)"
        }
    } else {
        "rumbo ${bearing.toInt()}°"
    }
    val modeNote = when {
        placement == Placement.Local -> "DEMO ~25–30 m · pulsa GPS real para distancia GPS"
        gpsLocked -> "GPS ANCLADO · no salta"
        placement == Placement.Geospatial -> "posición Geospatial"
        placement == Placement.Gps -> "GPS libre (puede saltar)"
        placement == Placement.Manual -> "anclado al piso"
        else -> "buscando GPS…"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Color(0xCC0F172A), RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Text(
            "TÚ (GPS) → terreno: ${GeoMath.formatDistance(distance)} · $headingHint",
            color = Color(0xFF86EFAC),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
        Text(
            "Precisión ±${pose.accuracyMeters.toInt()} m · $modeNote",
            color = Color(0xFFCBD5E1),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Marcador verde = tu GPS. Naranja = sólido del IFC (ubicación real).",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (distance > 150 && placement != Placement.Local) {
            Text(
                "Estás lejos del terreno. El sólido está a ${GeoMath.formatDistance(distance)} " +
                    "en esa dirección — no en el centro de la cámara. «Traer aquí» solo es demo.",
                color = Color(0xFFFBBF24),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun StatusChip(
    placement: Placement,
    accuracy: Double?,
    hasApiKey: Boolean,
    calibRefs: Int,
    calibrating: Boolean,
    solidParts: Int,
    showSolid: Boolean,
    polygonCount: Int,
) {
    val label = when {
        calibrating -> "Apunta la mira naranja al piso"
        calibRefs >= 2 -> "✓ Anclado al piso (2 refs)"
        calibRefs == 1 -> "Anclado parcial (1 ref)"
        showSolid && polygonCount > 0 && solidParts == 0 && placement != Placement.None ->
            "Sólido no dibujado · pulsa Traer aquí"
        showSolid && solidParts > 0 ->
            "Sólido ON ($solidParts piezas) · ${placementLabel(placement, accuracy)}"
        placement == Placement.Local -> "Sólido en modo local (traído aquí)"
        placement == Placement.Geospatial -> "Geospatial OK · ±${accuracy?.toInt() ?: "?"} m"
        placement == Placement.Gps -> "GPS + brújula · ±${accuracy?.toInt() ?: "?"} m"
        !hasApiKey -> "Sin API key ARCore · solo GPS"
        else -> "Iniciando AR… mueve el móvil despacio"
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

private fun placementLabel(placement: Placement, accuracy: Double?): String = when (placement) {
    Placement.Geospatial -> "Geo ±${accuracy?.toInt() ?: "?"}m"
    Placement.Gps -> "GPS"
    Placement.Manual -> "anclado"
    Placement.Local -> "local"
    Placement.None -> "…"
}

@Composable
private fun PointHud(point: GeoPoint, pose: DevicePose?) {
    val distance = pose?.let { GeoMath.distanceMeters(it.coordinate, point.coordinate) }
    val bearing = pose?.let { GeoMath.bearingDegrees(it.coordinate, point.coordinate) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xCC0F172A), MaterialTheme.shapes.medium)
            .padding(12.dp),
    ) {
        Text(point.name, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Row {
            Text(
                distance?.let { GeoMath.formatDistance(it) } ?: "Buscando GPS…",
                color = Color(0xFF38BDF8),
                modifier = Modifier.padding(end = 12.dp),
            )
            if (bearing != null && pose?.hasHeading == true) {
                Text("Rumbo ${bearing.toInt()}°", color = Color.White)
            }
        }
        if (pose != null) {
            Text("Precisión GPS ±${pose.accuracyMeters.toInt()} m", color = Color(0xFFCBD5E1))
        }
    }
}

@Composable
private fun OverlayGeometry(
    document: KmzDocument,
    targets: List<GeoPoint>,
    pose: DevicePose?,
    selectedId: String?,
) {
    if (pose == null || !pose.hasHeading) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val fov = 65f
        fun project(coord: LatLngAlt): Offset? {
            val delta = GeoMath.wrappedDeltaDegrees(
                GeoMath.bearingDegrees(pose.coordinate, coord),
                pose.headingDegrees.toDouble(),
            )
            if (kotlin.math.abs(delta) > fov) return null
            val distance = GeoMath.distanceMeters(pose.coordinate, coord)
            val x = size.width / 2f + (delta / fov) * (size.width / 2f)
            val y = size.height * 0.40f + (distance.toFloat().coerceAtMost(120f) * 1.1f)
            return Offset(x, y)
        }
        document.polygons.forEach { polygon ->
            val ring = openRing(polygon.ring)
            val projected = ring.map { project(it) }
            for (i in ring.indices) {
                val a = projected[i] ?: continue
                val b = projected[(i + 1) % ring.size] ?: continue
                drawLine(color = Color(0xFF34D399), start = a, end = b, strokeWidth = 6f)
            }
        }
        targets.forEach { point ->
            val pos = project(point.coordinate) ?: return@forEach
            drawCircle(
                color = if (point.id == selectedId) Color(0xFF38BDF8) else Color.White,
                radius = if (point.id == selectedId) 18f else 12f,
                center = pos,
            )
        }
    }
}

@Composable
private fun CompassNeedle(
    heading: Float,
    targetBearing: Float,
    modifier: Modifier = Modifier,
) {
    val rotation = GeoMath.wrappedDeltaDegrees(targetBearing.toDouble(), heading.toDouble())
    Canvas(modifier = modifier) {
        drawCircle(color = Color(0xAA0F172A), radius = size.minDimension / 2f)
        drawCircle(color = Color.White, radius = size.minDimension / 2f, style = Stroke(width = 3f))
        val path = Path().apply {
            moveTo(size.width / 2f, 8f)
            lineTo(size.width / 2f + 14f, size.height - 12f)
            lineTo(size.width / 2f - 14f, size.height - 12f)
            close()
        }
        rotate(degrees = rotation, pivot = Offset(size.width / 2f, size.height / 2f)) {
            drawPath(path, Color(0xFF38BDF8))
        }
    }
}

private fun hasArPermissions(context: android.content.Context): Boolean {
    val camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    return camera == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        fine == android.content.pm.PackageManager.PERMISSION_GRANTED
}
