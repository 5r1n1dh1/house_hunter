package com.househunter.app.ui.workmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkMapScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: WorkMapViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()

    val focus = state.activeLocation
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            focus?.let { LatLng(it.lat, it.lng) } ?: LatLng(12.9716, 77.5946),
            12f,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Work locations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(horizontal = 16.dp)) {
                SegmentedButton(
                    selected = state.activeTarget == 0,
                    onClick = { viewModel.setTarget(0) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Work 1") }
                SegmentedButton(
                    selected = state.activeTarget == 1,
                    onClick = { viewModel.setTarget(1) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Work 2") }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    singleLine = true,
                    label = { Text("Search office / area") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::search, enabled = !state.searching) {
                    if (state.searching) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                }
                IconButton(onClick = { viewModel.clear(state.activeTarget) }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                }
            }

            state.results.firstOrNull()?.let { result ->
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = { viewModel.pick(result) }) {
                        Text("Use \"${result.label}\"")
                    }
                }
            }

            GoogleMap(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isBuildingEnabled = false),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    mapToolbarEnabled = false,
                    myLocationButtonEnabled = false,
                ),
            ) {
                state.work1?.let {
                    Marker(state = MarkerState(LatLng(it.lat, it.lng)), title = "Work 1")
                }
                state.work2?.let {
                    Marker(state = MarkerState(LatLng(it.lat, it.lng)), title = "Work 2")
                }
                state.results.forEach { result ->
                    Marker(
                        state = MarkerState(LatLng(result.lat, result.lng)),
                        title = result.label,
                    )
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Work 1: ${state.work1?.label ?: "not set"}" +
                        (state.work1?.let { " (${it.lat}, ${it.lng})" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Work 2: ${state.work2?.label ?: "not set"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { viewModel.save(onDone) },
                    enabled = state.canSave,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save work locations") }
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Skip for now")
                }
            }
        }
    }
}