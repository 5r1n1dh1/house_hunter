package com.househunter.app.ui.preferences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.househunter.app.domain.model.OsmPlace
import com.househunter.app.ui.components.PickerSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesScreen(
    onContinue: () -> Unit,
    viewModel: PreferencesViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Where do you want to live?") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "States and districts load instantly from a bundled OpenStreetMap " +
                    "snapshot. Localities are searched live (OpenStreetMap Nominatim).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PickerRow(
                label = "State",
                value = state.selectedState?.name,
                enabled = true,
                onClick = { sheet = SheetTarget.STATE },
            )
            PickerRow(
                label = "District",
                value = state.selectedDistrict?.name,
                enabled = state.selectedState != null && !state.loading,
                onClick = { sheet = SheetTarget.DISTRICT },
            )
            PickerRow(
                label = "Locality",
                value = state.selectedLocality?.name,
                enabled = state.selectedDistrict != null,
                onClick = { sheet = SheetTarget.LOCALITY },
            )

            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            state.error?.let { message ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.save(onContinue) },
                enabled = viewModel.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue")
            }
        }
    }

    when (sheet) {
        SheetTarget.STATE -> PickerSheet(
            title = "Select state",
            items = state.states,
            label = { it.name },
            isSelected = { it.osmId == state.selectedState?.osmId },
            onPick = viewModel::selectState,
            onDismiss = { sheet = null },
        )

        SheetTarget.DISTRICT -> PickerSheet(
            title = "Select district",
            items = state.districts,
            label = { it.name },
            isSelected = { it.osmId == state.selectedDistrict?.osmId },
            onPick = viewModel::selectDistrict,
            onDismiss = { sheet = null },
        )

        SheetTarget.LOCALITY -> LocalitySearchSheet(
            query = state.query,
            results = state.results,
            searched = state.searching,
            browsed = state.browsed,
            browsing = state.browsing,
            onQueryChange = viewModel::onQueryChange,
            onPick = viewModel::selectLocality,
            onUseTyped = { viewModel.addManual(it); sheet = null },
            onBrowseAll = viewModel::browseAll,
            onDismiss = { sheet = null },
        )

        null -> Unit
    }
}

private enum class SheetTarget { STATE, DISTRICT, LOCALITY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalitySearchSheet(
    query: String,
    results: List<OsmPlace>,
    searched: Boolean,
    browsed: List<OsmPlace>,
    browsing: Boolean,
    onQueryChange: (String) -> Unit,
    onPick: (OsmPlace) -> Unit,
    onUseTyped: (String) -> Unit,
    onBrowseAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Select locality", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Type at least 3 letters") },
                supportingText = { Text("Searches OpenStreetMap — usually under a second") },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(onClick = { onUseTyped(query) }, enabled = query.trim().length >= 2) {
                    Text("Use \"${query.trim()}\"")
                }
                TextButton(onClick = onBrowseAll, enabled = !browsing) {
                    Text(if (browsing) "Loading all…" else "Browse all (slow)")
                }
            }

            if (searched) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Searching…", style = MaterialTheme.typography.bodySmall)
                }
            }

            val shown = results.ifEmpty { browsed }
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(shown, key = { "${it.osmId}:${it.name}" }) { place ->
                    Text(
                        text = place.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(place)
                                onDismiss()
                            }
                            .padding(horizontal = 4.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}