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
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    onOpenIfc3d: (KmzDocument) -> Unit,
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
                        "application/x-step",
                        "model/ifc",
                        "*/*",
                    ),
                )
            }) {
                Icon(Icons.Outlined.UploadFile, contentDescription = "Importar KMZ/IFC")
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
                OutlinedButton(
                    onClick = onImportArequipaSample,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cargar polígono Arequipa + sólido 3D")
                }
            }
            if (state.documents.isEmpty()) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Importa KMZ, KML o IFC. En IFC puedes abrir el visor 3D o, si está georreferenciado, el mapa/AR.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedButton(onClick = onImportSample) {
                        Text("Cargar ejemplo de puntos")
                    }
                    OutlinedButton(onClick = onImportPolygonSample) {
                        Text("Cargar ejemplo de polígono (Google Earth)")
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.documents, key = { it.id }) { document ->
                        DocumentCard(
                            document = document,
                            onOpen = { onOpen(document) },
                            onOpenIfc3d = { onOpenIfc3d(document) },
                            onDelete = { onDelete(document) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DocumentCard(
    document: KmzDocument,
    onOpen: () -> Unit,
    onOpenIfc3d: () -> Unit,
    onDelete: () -> Unit,
) {
    val canMap = document.arTargets().isNotEmpty() || document.polygons.isNotEmpty()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(document.fileName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            if (document.isIfc) {
                                append("IFC")
                                append(" · ${document.localMeshes.size} malla(s)")
                                if (document.isGeoreferenced) append(" · georref.")
                                else append(" · local")
                                document.solidHeightMeters?.let { append(" · ${it.toInt()} m") }
                            } else {
                                append("${document.points.size} puntos · ${document.lines.size} líneas · ${document.polygons.size} polígonos")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Eliminar")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (document.isIfc && document.localMeshes.isNotEmpty()) {
                    Button(
                        onClick = onOpenIfc3d,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEA580C)),
                    ) {
                        Icon(Icons.Outlined.ViewInAr, contentDescription = null)
                        Text("  Ver IFC 3D")
                    }
                }
                OutlinedButton(
                    onClick = onOpen,
                    enabled = canMap,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Map, contentDescription = null)
                    Text(if (document.isIfc) "  Mapa / AR" else "  Abrir")
                }
            }
            if (document.isIfc && !document.isGeoreferenced) {
                Text(
                    "Sin georreferencia: puedes verlo en 3D. Para mapa/AR necesita IfcSite o IfcMapConversion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
