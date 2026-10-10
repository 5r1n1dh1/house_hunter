package com.househunter.app.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
                text = "All three lists come from OpenStreetMap (Overpass API).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PickerRow(
                label = "State",
                value = state.selectedState?.name,
                enabled = !state.loading,
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
                enabled = state.selectedDistrict != null && !state.loading,
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
                    TextButton(onClick = viewModel::retry) { Text("Retry") }
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { viewModel.save(onContinue) },
                enabled = viewModel.canSave && !state.loading,
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

        SheetTarget.LOCALITY -> PickerSheet(
            title = "Select locality",
            items = state.localities,
            label = { it.name },
            isSelected = { it.osmId == state.selectedLocality?.osmId },
            onPick = viewModel::selectLocality,
            onDismiss = { sheet = null },
        )

        null -> Unit
    }
}

private enum class SheetTarget { STATE, DISTRICT, LOCALITY }

@Composable
private fun PickerRow(
    label: String,
    value: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(
                    text = value ?: "Select",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (value != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = "▾",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}