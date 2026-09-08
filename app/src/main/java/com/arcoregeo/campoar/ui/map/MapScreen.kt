package com.arcoregeo.campoar.ui.map

import android.graphics.PointF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.data.OfflineMapStore
import com.arcoregeo.campoar.data.openRing
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
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
    onBack: () -> Unit,
    onDownloadOffline: (Float) -> Unit,
    onOpenAr: () -> Unit,
    onSelectPoint: (String) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val mapView = remember { MapView(context) }

    DisposableEffect(document.id) {
        mapView.onCreate(null)
        mapView.onStart()
        mapView.onResume()
        mapView.getMapAsync { map ->
            fun applyFeatures(style: Style) {
                // Remove previous layers/sources if re-entering
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

                val allLatLng = buildList {
                    targets.forEach { add(LatLng(it.coordinate.latitude, it.coordinate.longitude)) }
                    document.lines.forEach { line ->
                        line.coordinates.forEach { add(LatLng(it.latitude, it.longitude)) }
                    }
                    document.polygons.forEach { poly ->
                        poly.ring.forEach { add(LatLng(it.latitude, it.longitude)) }
                    }
                }
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
                        // If polygon tapped, select its centroid/first vertex target
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
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
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
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (downloadProgress != null) {
                    Text("Descargando mapa: $downloadProgress%")
                    LinearProgressIndicator(
                        progress = downloadProgress / 100f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
                Button(
                    onClick = { onDownloadOffline(density.density) },
                    enabled = downloadProgress == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                    Text("  Descargar mapa para usar sin red")
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
