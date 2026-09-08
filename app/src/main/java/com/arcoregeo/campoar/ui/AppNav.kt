package com.arcoregeo.campoar.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arcoregeo.campoar.ui.ar.ArScreen
import com.arcoregeo.campoar.ui.home.HomeScreen
import com.arcoregeo.campoar.ui.map.MapScreen
import com.arcoregeo.campoar.viewmodel.CampoViewModel

@Composable
fun CampoArRoot(viewModel: CampoViewModel) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.consumeMessage()
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                HomeScreen(
                    state = state,
                    onImport = viewModel::importShared,
                    onImportSample = viewModel::importSample,
                    onImportPolygonSample = viewModel::importPolygonSample,
                    onImportArequipaSample = viewModel::importArequipaSample,
                    onOpen = { document ->
                        viewModel.select(document)
                        navController.navigate("map/${document.id}")
                    },
                    onDelete = viewModel::delete,
                )
            }
            composable(
                route = "map/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                val document = state.documents.firstOrNull { it.id == id } ?: state.selected
                if (document != null) {
                    MapScreen(
                        document = document,
                        downloadProgress = state.downloadProgress,
                        onBack = { navController.popBackStack() },
                        onDownloadOffline = viewModel::downloadOfflineMap,
                        onOpenAr = { navController.navigate("ar/${document.id}") },
                        onSelectPoint = viewModel::selectPoint,
                    )
                }
            }
            composable(
                route = "ar/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("id")
                val document = state.documents.firstOrNull { it.id == id } ?: state.selected
                if (document != null) {
                    ArScreen(
                        document = document,
                        state = state,
                        onBack = { navController.popBackStack() },
                        onStartLocation = viewModel::startLocation,
                        onStopLocation = viewModel::stopLocation,
                        onSelectPoint = viewModel::selectPoint,
                        onGeospatialStatus = viewModel::setGeospatialStatus,
                        onCalibrationChanged = viewModel::setCalibrationRefCount,
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbar)
    }
}
