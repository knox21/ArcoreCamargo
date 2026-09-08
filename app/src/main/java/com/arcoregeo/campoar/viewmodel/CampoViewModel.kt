package com.arcoregeo.campoar.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arcoregeo.campoar.data.GeoPoint
import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.data.KmzRepository
import com.arcoregeo.campoar.data.OfflineMapStore
import com.arcoregeo.campoar.geo.DevicePose
import com.arcoregeo.campoar.geo.GeoMath
import com.arcoregeo.campoar.geo.LocationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CampoUiState(
    val documents: List<KmzDocument> = emptyList(),
    val selected: KmzDocument? = null,
    val selectedPointId: String? = null,
    val pose: DevicePose? = null,
    val message: String? = null,
    val importing: Boolean = false,
    val downloadProgress: Int? = null,
    val geospatialTracking: Boolean = false,
    val geospatialHorizontalAccuracy: Double? = null,
    val geospatialEarthState: String? = null,
    val calibrationRefCount: Int = 0,
)

class CampoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = KmzRepository(application)
    private val locationRepository = LocationRepository(application)
    private val offlineMapStore = OfflineMapStore(application)

    private val _state = MutableStateFlow(CampoUiState())
    val state: StateFlow<CampoUiState> = _state.asStateFlow()

    private var locationJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repository.listDocuments() }
                .onSuccess { docs -> _state.update { it.copy(documents = docs) } }
                .onFailure { error -> _state.update { it.copy(message = error.message) } }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    fun importSample() {
        importAsset("sample/ejemplo_puntos.kml", "ejemplo_puntos.kml")
    }

    fun importPolygonSample() {
        importAsset("sample/ejemplo_poligono.kml", "ejemplo_poligono.kml")
    }

    fun importArequipaSample() {
        importAsset("sample/poligono_arequipa.kml", "poligono_arequipa.kml")
    }

    fun setCalibrationRefCount(count: Int) {
        _state.update { it.copy(calibrationRefCount = count) }
    }

    private fun importAsset(assetPath: String, displayName: String) {
        viewModelScope.launch {
            _state.update { it.copy(importing = true, message = null) }
            runCatching {
                getApplication<Application>().assets.open(assetPath).use { stream ->
                    repository.importFromStream(displayName, stream)
                }
            }.onSuccess { doc ->
                _state.update {
                    it.copy(
                        documents = repository.listDocuments(),
                        selected = doc,
                        selectedPointId = doc.arTargets().firstOrNull()?.id,
                        importing = false,
                        message = "Ejemplo: ${doc.polygons.size} polígonos, ${doc.arTargets().size} marcas AR",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(importing = false, message = error.message) }
            }
        }
    }

    fun importShared(uri: Uri, displayName: String, mimeType: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(importing = true, message = null) }
            val isIfc = runCatching {
                repository.looksLikeIfc(uri, displayName, mimeType)
            }.getOrDefault(false)
            if (isIfc) {
                importIfc(uri, displayName)
                return@launch
            }
            runCatching { repository.importFromUri(uri, displayName) }
                .onSuccess { doc ->
                    _state.update {
                        it.copy(
                            documents = repository.listDocuments(),
                            selected = doc,
                            selectedPointId = doc.arTargets().firstOrNull()?.id,
                            importing = false,
                            message = "Se importó ${doc.fileName}: ${doc.points.size} puntos, ${doc.polygons.size} polígonos, ${doc.arTargets().size} marcas AR",
                        )
                    }
                }
                .onFailure { error ->
                    if (error.message == "IFC_FILE") {
                        importIfc(uri, displayName)
                    } else {
                        _state.update { it.copy(importing = false, message = error.message) }
                    }
                }
        }
    }

    private suspend fun importIfc(uri: Uri, displayName: String) {
        runCatching { repository.importIfcFromUri(uri, displayName) }
            .onSuccess { doc ->
                val geo = if (doc.isGeoreferenced) "georref." else "solo local"
                val meshes = doc.localMeshes.size
                val verts = doc.polygons.firstOrNull()?.let { openRingSize(it.ring) } ?: 0
                _state.update {
                    it.copy(
                        documents = repository.listDocuments(),
                        selected = doc,
                        selectedPointId = doc.arTargets().firstOrNull()?.id,
                        importing = false,
                        message = "IFC: ${doc.fileName} · $meshes malla(s) · $geo" +
                            if (verts > 0) " · $verts vértices mapa" else " · usa Ver IFC 3D",
                    )
                }
            }
            .onFailure { error ->
                _state.update {
                    it.copy(
                        importing = false,
                        message = "No se pudo leer el IFC: ${error.message}",
                    )
                }
            }
    }

    private fun openRingSize(ring: List<com.arcoregeo.campoar.data.LatLngAlt>): Int {
        if (ring.size < 2) return ring.size
        val first = ring.first()
        val last = ring.last()
        return if (first.latitude == last.latitude && first.longitude == last.longitude) {
            ring.size - 1
        } else {
            ring.size
        }
    }

    fun select(document: KmzDocument) {
        _state.update {
            it.copy(
                selected = document,
                selectedPointId = document.arTargets().firstOrNull()?.id,
            )
        }
    }

    fun selectPoint(pointId: String) {
        _state.update { it.copy(selectedPointId = pointId) }
    }

    fun delete(document: KmzDocument) {
        viewModelScope.launch {
            repository.delete(document.id)
            refresh()
            _state.update { current ->
                current.copy(
                    selected = current.selected?.takeIf { it.id != document.id },
                    message = "Se eliminó ${document.fileName}",
                )
            }
        }
    }

    fun startLocation() {
        if (!locationRepository.hasFineLocation()) return
        if (locationJob != null) return
        locationJob = viewModelScope.launch {
            locationRepository.poseFlow().collect { pose ->
                _state.update { it.copy(pose = pose) }
            }
        }
    }

    fun stopLocation() {
        locationJob?.cancel()
        locationJob = null
    }

    fun setGeospatialStatus(tracking: Boolean, horizontalAccuracy: Double?, earthState: String? = null) {
        val current = _state.value
        val rounded = horizontalAccuracy?.toInt()
        if (current.geospatialTracking == tracking &&
            current.geospatialHorizontalAccuracy?.toInt() == rounded &&
            current.geospatialEarthState == earthState
        ) {
            return
        }
        _state.update {
            it.copy(
                geospatialTracking = tracking,
                geospatialHorizontalAccuracy = horizontalAccuracy,
                geospatialEarthState = earthState,
            )
        }
    }

    fun downloadOfflineMap(pixelRatio: Float) {
        val document = _state.value.selected ?: return
        viewModelScope.launch {
            _state.update { it.copy(downloadProgress = 0, message = null) }
            runCatching {
                offlineMapStore.downloadForDocument(document, pixelRatio) { percent ->
                    _state.update { it.copy(downloadProgress = percent) }
                }
            }.onSuccess {
                _state.update {
                    it.copy(downloadProgress = null, message = "Mapa listo para usar sin red")
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(downloadProgress = null, message = error.message)
                }
            }
        }
    }

    fun nearestPoint(document: KmzDocument, pose: DevicePose?): Pair<GeoPoint, Double>? {
        pose ?: return null
        return document.points.minByOrNull { GeoMath.distanceMeters(pose.coordinate, it.coordinate) }
            ?.let { it to GeoMath.distanceMeters(pose.coordinate, it.coordinate) }
    }
}
