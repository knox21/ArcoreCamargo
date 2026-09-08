package com.arcoregeo.campoar.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcoregeo.campoar.data.KmzDocument
import com.arcoregeo.campoar.viewmodel.CampoUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: CampoUiState,
    onImport: (Uri, String) -> Unit,
    onImportSample: () -> Unit,
    onImportPolygonSample: () -> Unit,
    onImportArequipaSample: () -> Unit,
    onOpen: (KmzDocument) -> Unit,
    onDelete: (KmzDocument) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            onImport(uri, uri.lastPathSegment ?: "archivo.kmz")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("CampoAR") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                picker.launch(
                    arrayOf(
                        "application/vnd.google-earth.kmz",
                        "application/vnd.google-earth.kml+xml",
                        "application/xml",
                        "text/xml",
                        "*/*",
                    ),
                )
            }) {
                Icon(Icons.Outlined.UploadFile, contentDescription = "Importar KMZ")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.importing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onImportArequipaSample,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cargar polígono Arequipa + sólido 3D")
                }
            }
            if (state.documents.isEmpty()) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Importa un KMZ o KML. En AR: fija ref 1 tocando el piso en un vértice para estabilizar. El sólido 3D (IFC/GLB) se ancla con esa referencia.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    androidx.compose.material3.OutlinedButton(onClick = onImportSample) {
                        Text("Cargar ejemplo de puntos")
                    }
                    androidx.compose.material3.OutlinedButton(onClick = onImportPolygonSample) {
                        Text("Cargar ejemplo de polígono (Google Earth)")
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.documents, key = { it.id }) { document ->
                        Card(onClick = { onOpen(document) }, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(document.fileName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${document.points.size} puntos · ${document.lines.size} líneas · ${document.polygons.size} polígonos" +
                                            if (document.fileName.contains("arequipa", ignoreCase = true) ||
                                                document.polygons.isNotEmpty()
                                            ) " · Sólido 3D listo" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Icon(Icons.Outlined.Map, contentDescription = null)
                                IconButton(onClick = { onDelete(document) }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Eliminar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
