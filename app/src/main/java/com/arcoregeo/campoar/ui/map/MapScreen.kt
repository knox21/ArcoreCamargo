package com.arcoregeo.campoar.ui.map

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.data.OfflineMapStore
import com.arcoregeo.campoar.data.openRing
import com.arcoregeo.campoar.geo.DevicePose
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    document: KmzDocument,
    downloadProgress: Int?,
    myPose: DevicePose?,
    gpsCalibrating: Boolean,
    gpsLocked: Boolean,
    onBack: () -> Unit,
    onDownloadOffline: (Float) -> Unit,
    onOpenAr: () -> Unit,
    onSelectPoint: (String) -> Unit,
    onStartLocation: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val mapView = remember { MapView(context) }
    var mapLibre by remember { mutableStateOf<MapLibreMap?>(null) }
    var locationHint by remember { mutableStateOf<String?>(null) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasLocationPermission = result.values.any { it }
        if (hasLocationPermission) onStartLocation()
    }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) onStartLocation()
        else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    fun documentLatLngs(): List<LatLng> = buildList {
        document.arTargets().forEach {
            add(LatLng(it.coordinate.latitude, it.coordinate.longitude))
        }
        document.lines.forEach { line ->
            line.coordinates.forEach { add(LatLng(it.latitude, it.longitude)) }
        }
        document.polygons.forEach { poly ->
            poly.ring.forEach { add(LatLng(it.latitude, it.longitude)) }
        }
    }

    fun centerOnMeAndModel() {
        val map = mapLibre ?: return
        val points = documentLatLngs().toMutableList()
        val me = myPose?.coordinate?.let { LatLng(it.latitude, it.longitude) }
            ?: runCatching {
                map.locationComponent.lastKnownLocation?.let { loc ->
                    LatLng(loc.latitude, loc.longitude)
                }
            }.getOrNull()
        if (me != null) {
            points += me
            locationHint = null
        } else {
            locationHint = "Buscando GPS… activa ubicación y vuelve a tocar"
        }
        when {
            points.size >= 2 -> {
                val bounds = LatLngBounds.Builder().apply { points.forEach { include(it) } }.build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 140))
            }
            points.size == 1 -> {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 17.0))
            }
            else -> locationHint = "Sin geometría ni GPS todavía"
        }
    }

    fun centerOnMeOnly() {
        val map = mapLibre ?: return
        val me = myPose?.coordinate?.let { LatLng(it.latitude, it.longitude) }
            ?: runCatching {
                map.locationComponent.lastKnownLocation?.let { loc ->
                    LatLng(loc.latitude, loc.longitude)
                }
            }.getOrNull()
        if (me == null) {
            locationHint = "Sin GPS aún · espera un momento"
            return
        }
        locationHint = null
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(me, 18.0))
    }

    DisposableEffect(document.id) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { map ->
            mapLibre = map
            fun applyFeatures(style: Style) {
                listOf(
                    "kmz-polygons-fill", "kmz-polygons-outline", "kmz-lines-layer", "kmz-points-layer",
                ).forEach { id -> runCatching { style.removeLayer(id) } }
                listOf("kmz-points", "kmz-lines", "kmz-polygons", "kmz-polygon-outlines").forEach { id ->
                    runCatching { style.removeSource(id) }
                }

                val targets = document.arTargets()
                val pointFeatures = targets.map { point ->
                    Feature.fromGeometry(
                        Point.fromLngLat(point.coordinate.longitude, point.coordinate.latitude),
                    ).apply {
                        addStringProperty("id", point.id)
                        addStringProperty("name", point.name)
                    }
                }
                val lineFeatures = document.lines.map { line ->
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            line.coordinates.map { Point.fromLngLat(it.longitude, it.latitude) },
                        ),
                    )
                }
                val polygonFeatures = document.polygons.mapNotNull { polygon ->
                    val open = openRing(polygon.ring)
                    if (open.size < 3) return@mapNotNull null
                    val ring = open.map { Point.fromLngLat(it.longitude, it.latitude) }
                    val closed = ring + ring.first()
                    Feature.fromGeometry(Polygon.fromLngLats(listOf(closed))).apply {
                        addStringProperty("id", polygon.id)
                        addStringProperty("name", polygon.name)
                    }
                }
                val outlineFeatures = document.polygons.mapNotNull { polygon ->
                    val open = openRing(polygon.ring)
                    if (open.size < 2) return@mapNotNull null
                    val ring = open.map { Point.fromLngLat(it.longitude, it.latitude) }
                    val closed = ring + ring.first()
                    Feature.fromGeometry(LineString.fromLngLats(closed)).apply {
                        addStringProperty("id", polygon.id)
                        addStringProperty("name", polygon.name)
                    }
                }

                style.addSource(GeoJsonSource("kmz-points", FeatureCollection.fromFeatures(pointFeatures)))
                style.addSource(GeoJsonSource("kmz-lines", FeatureCollection.fromFeatures(lineFeatures)))
                style.addSource(GeoJsonSource("kmz-polygons", FeatureCollection.fromFeatures(polygonFeatures)))
                style.addSource(GeoJsonSource("kmz-polygon-outlines", FeatureCollection.fromFeatures(outlineFeatures)))

                style.addLayer(
                    FillLayer("kmz-polygons-fill", "kmz-polygons").withProperties(
                        fillColor("#34D399"),
                        fillOpacity(0.45f),
                    ),
                )
                style.addLayer(
                    LineLayer("kmz-polygons-outline", "kmz-polygon-outlines").withProperties(
                        lineColor("#10B981"),
                        lineWidth(4f),
                    ),
                )
                style.addLayer(
                    LineLayer("kmz-lines-layer", "kmz-lines").withProperties(
                        lineColor("#38BDF8"),
                        lineWidth(3f),
                    ),
                )
                style.addLayer(
                    CircleLayer("kmz-points-layer", "kmz-points").withProperties(
                        circleRadius(8f),
                        circleColor("#38BDF8"),
                        circleStrokeColor("#FFFFFF"),
                        circleStrokeWidth(2f),
                    ),
                )

                val allLatLng = documentLatLngs()
                if (allLatLng.size == 1) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(allLatLng.first(), 17.0))
                } else if (allLatLng.size >= 2) {
                    val bounds = LatLngBounds.Builder().apply {
                        allLatLng.forEach { include(it) }
                    }.build()
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
                }

                map.addOnMapClickListener { latLng ->
                    val screen: PointF = map.projection.toScreenLocation(latLng)
                    val hit = map.queryRenderedFeatures(screen, "kmz-points-layer", "kmz-polygons-fill")
                    val id = hit.firstOrNull()?.getStringProperty("id")
                    if (id != null) {
                        val targetId = targets.firstOrNull { it.id == id || it.id.startsWith("$id-") }?.id
                            ?: id
                        onSelectPoint(targetId)
                        true
                    } else {
                        false
                    }
                }
            }

            map.setStyle(Style.Builder().fromUri(OfflineMapStore.STYLE_URL)) { style ->
                applyFeatures(style)
                @Suppress("MissingPermission")
                if (hasLocationPermission) {
                    runCatching {
                        val locationComponent = map.locationComponent
                        val activation = org.maplibre.android.location.LocationComponentActivationOptions
                            .builder(context, style)
                            .useDefaultLocationEngine(true)
                            .build()
                        locationComponent.activateLocationComponent(activation)
                        locationComponent.isLocationComponentEnabled = true
                        locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
                        locationComponent.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
                    }
                }
            }
        }
        onDispose {
            mapLibre = null
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Re-enable blue location puck if permission arrives after style load.
    LaunchedEffect(hasLocationPermission, mapLibre) {
        val map = mapLibre ?: return@LaunchedEffect
        if (!hasLocationPermission) return@LaunchedEffect
        @Suppress("MissingPermission")
        runCatching {
            val style = map.style ?: return@runCatching
            val locationComponent = map.locationComponent
            if (!locationComponent.isLocationComponentActivated) {
                val activation = org.maplibre.android.location.LocationComponentActivationOptions
                    .builder(context, style)
                    .useDefaultLocationEngine(true)
                    .build()
                locationComponent.activateLocationComponent(activation)
            }
            locationComponent.isLocationComponentEnabled = true
            locationComponent.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
        }
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
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FloatingActionButton(
                    onClick = { centerOnMeOnly() },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(Icons.Outlined.MyLocation, contentDescription = "Centrar en mí")
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    text = buildString {
                        append("${document.points.size} puntos · ${document.lines.size} líneas · ${document.polygons.size} polígonos · ${document.arTargets().size} marcas AR")
                        if (document.polygons.isNotEmpty()) append(" · Sólido 3D listo")
                        myPose?.let {
                            append(" · GPS ±${it.accuracyMeters.toInt()} m")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                locationHint?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(
                    text = when {
                        gpsLocked ->
                            "GPS anclado · quédate en este punto · la cámara respeta el rumbo real"
                        gpsCalibrating ->
                            "Calibrando GPS · quédate parado, se promedian las lecturas y se ancla"
                        myPose == null ->
                            "Buscando GPS…"
                        else ->
                            "GPS ±${myPose.accuracyMeters.toInt()} m"
                    },
                    color = if (gpsLocked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (downloadProgress != null) {
                    Text("Descargando mapa: $downloadProgress%")
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { centerOnMeAndModel() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.MyLocation, contentDescription = null)
                        Text("  Yo + modelo")
                    }
                    Button(
                        onClick = { onDownloadOffline(density.density) },
                        enabled = downloadProgress == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                        Text("  Offline")
                    }
                }
                Button(
                    onClick = onOpenAr,
                    enabled = document.arTargets().isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Icon(Icons.Outlined.ViewInAr, contentDescription = null)
                    Text("  Abrir cámara AR")
                }
            }
        }
    }
}
