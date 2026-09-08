package com.arcoregeo.campoar.data

import android.content.Context
import com.arcoregeo.campoar.geo.GeoMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class OfflineMapStore(context: Context) {
    private val appContext = context.applicationContext

    suspend fun downloadForDocument(
        document: KmzDocument,
        pixelRatio: Float,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val bounds = document.boundsOrNull() ?: error("El KMZ no tiene coordenadas para descargar mapa")
        val padded = GeoMath.expandBounds(bounds.first, bounds.second)
        val definition = OfflineTilePyramidRegionDefinition(
            STYLE_URL,
            LatLngBounds.from(
                padded.second.latitude,
                padded.second.longitude,
                padded.first.latitude,
                padded.first.longitude,
            ),
            MIN_ZOOM,
            MAX_ZOOM,
            pixelRatio,
        )
        val metadata = JSONObject().put("name", document.fileName).toString().toByteArray()
        val manager = OfflineManager.getInstance(appContext)
        val region = createRegion(manager, definition, metadata)
        observeDownload(region, onProgress)
    }

    private suspend fun createRegion(
        manager: OfflineManager,
        definition: OfflineTilePyramidRegionDefinition,
        metadata: ByteArray,
    ): OfflineRegion = suspendCoroutine { cont ->
        manager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    cont.resume(offlineRegion)
                }

                override fun onError(error: String) {
                    cont.resumeWithException(IllegalStateException(error))
                }
            },
        )
    }

    private suspend fun observeDownload(
        region: OfflineRegion,
        onProgress: (Int) -> Unit,
    ) = suspendCoroutine { cont ->
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                val complete = status.requiredResourceCount
                val completed = status.completedResourceCount
                val percent = if (complete > 0) {
                    ((completed.toDouble() / complete.toDouble()) * 100).toInt().coerceIn(0, 100)
                } else {
                    0
                }
                onProgress(percent)
                if (status.isComplete) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.setObserver(null)
                    cont.resume(Unit)
                }
            }

            override fun onError(error: OfflineRegionError) {
                region.setObserver(null)
                cont.resumeWithException(IllegalStateException(error.message ?: "Error offline"))
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                region.setObserver(null)
                cont.resumeWithException(
                    IllegalStateException("La zona es demasiado grande para descargar ($limit teselas)."),
                )
            }
        })
    }

    companion object {
        const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        const val MIN_ZOOM = 8.0
        const val MAX_ZOOM = 16.0
    }
}

fun KmzDocument.centerOrNull(): LatLng? {
    val bounds = boundsOrNull() ?: return null
    return LatLng(
        (bounds.first.latitude + bounds.second.latitude) / 2.0,
        (bounds.first.longitude + bounds.second.longitude) / 2.0,
    )
}
