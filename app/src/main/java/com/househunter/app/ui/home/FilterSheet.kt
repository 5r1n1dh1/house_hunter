package com.househunter.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.househunter.app.domain.model.Filters
import com.househunter.app.domain.model.UserPrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    prefs: UserPrefs,
    onDismiss: () -> Unit,
    onApply: (Filters) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initial = prefs.filters

    var rentMin by remember { mutableStateOf(initial.rentMin?.toFloat() ?: 0f) }
    var rentMax by remember { mutableStateOf(initial.rentMax?.toFloat() ?: 100_000f) }
    var bhk by remember { mutableStateOf(initial.bhk) }
    var km1 by remember { mutableStateOf((initial.maxKmWork1 ?: 15.0).toFloat()) }
    var km2 by remember { mutableStateOf((initial.maxKmWork2 ?: 15.0).toFloat()) }
    var contactOnly by remember { mutableStateOf(initial.contactOnly) }
    var useKm1 by remember { mutableStateOf(initial.maxKmWork1 != null && prefs.work1 != null) }
    var useKm2 by remember { mutableStateOf(initial.maxKmWork2 != null && prefs.work2 != null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Filters", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

            Text("Budget: ₹${rentMin.toInt()} – ₹${rentMax.toInt()}")
            Slider(
                value = rentMin,
                onValueChange = { rentMin = it.coerceAtMost(rentMax) },
                valueRange = 0f..100_000f,
            )
            Slider(
                value = rentMax,
                onValueChange = { rentMax = it.coerceAtLeast(rentMin) },
                valueRange = 1_000f..200_000f,
            )

            Text("Configuration")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 3, 4).forEach { option ->
                    FilterChip(
                        selected = option in bhk,
                        onClick = {
                            bhk = if (option in bhk) bhk - option else bhk + option
                        },
                        label = { Text(if (option == 4) "4+ BHK" else "$option BHK") },
                    )
                }
            }

            if (prefs.work1 != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Within ${km1.toInt()} km of Work 1")
                    Switch(checked = useKm1, onCheckedChange = { useKm1 = it })
                }
                if (useKm1) {
                    Slider(value = km1, onValueChange = { km1 = it }, valueRange = 1f..50f)
                }
            }

            if (prefs.work2 != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Within ${km2.toInt()} km of Work 2")
                    Switch(checked = useKm2, onCheckedChange = { useKm2 = it })
                }
                if (useKm2) {
                    Slider(value = km2, onValueChange = { km2 = it }, valueRange = 1f..50f)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Only listings with a phone number")
                Switch(checked = contactOnly, onCheckedChange = { contactOnly = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        rentMin = 0f; rentMax = 100_000f; bhk = emptySet()
                        km1 = 15f; km2 = 15f; contactOnly = false; useKm1 = false; useKm2 = false
                    },
                ) { Text("Reset") }
                Button(
                    onClick = {
                        onApply(
                            Filters(
                                rentMin = rentMin.toDouble(),
                                rentMax = rentMax.toDouble(),
                                bhk = bhk,
                                maxKmWork1 = if (useKm1) km1.toDouble() else null,
                                maxKmWork2 = if (useKm2) km2.toDouble() else null,
                                contactOnly = contactOnly,
                            ),
                        )
                        onDismiss()
                    },
                ) { Text("Apply") }
            }
        }
    }
}