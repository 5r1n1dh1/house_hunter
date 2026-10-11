package com.househunter.app.ui.workmap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.LatLng
import com.househunter.app.domain.model.WorkLocation
import com.househunter.app.ui.map.MapUnavailableWithAction
import com.househunter.app.ui.map.SafeGoogleMap
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf

/**
 * Work locations, rebuilt as three explicit steps.
 *
 * The old screen hid the flow: two tabs, a map, and no obvious "which am I editing?"
 * signal. Now the slot being edited is stated in the title, the search result is an
 * explicit confirm action, and completion is obvious.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkMapScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: WorkMapViewModel = hiltViewModel(),
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val editingLabel = if (state.activeTarget == 0) "Work 1" else "Work 2"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Setting $editingLabel")
                        Text(
                            text = when (state.activeTarget) {
                                0 -> "Step 2 of 3 · your main office"
                                else -> "Step 3 of 3 · an optional second office"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
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
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Step 1: choose the slot
            SlotPicker(
                selected = state.activeTarget,
                work1 = state.work1,
                work2 = state.work2,
                onSelect = viewModel::setTarget,
            )

            // Step 2: find the place
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search your office or area") },
                placeholder = { Text("e.g. Outer Ring Road, Bellandur") },
                shape = MaterialTheme.shapes.large,
                trailingIcon = {
                    IconButton(onClick = viewModel::search, enabled = !state.searching) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
            )

            // Results are a *list*, not a single auto-picked point: Nominatim's top
            // hit is often a bus stop rather than the campus the user meant.
            if (state.results.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Pick a location",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.results.take(MAX_RESULTS).forEach { candidate ->
                        ResultRow(
                            title = candidate.name,
                            subtitle = candidate.displayName,
                            onClick = { viewModel.pick(candidate) },
                        )
                    }
                }
            }

            // No match: say so, and offer the two ways in that always work.
            if (state.notFound && !state.searching) {
                Text(
                    text = "No match for \"${state.query.trim()}\". " +
                        "Private office parks are often missing from OpenStreetMap - " +
                        "paste the pin from Google Maps, or drop it on the map below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                FilledTonalButton(
                    onClick = { viewModel.setManualMode(true) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Enter coordinates manually")
                }
            }

            if (state.manualMode) {
                var manual by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste coordinates or Maps link") },
                    placeholder = { Text("12.9719, 77.6412") },
                    shape = MaterialTheme.shapes.large,
                )
                FilledTonalButton(
                    onClick = {
                        if (!viewModel.applyManual(manual)) {
                            Toast.makeText(context, "Could not read those coordinates", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("Use this location")
                }
            }

            state.activeLocation?.let { location ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$editingLabel set",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "${"%.4f".format(location.lat)}, ${"%.4f".format(location.lng)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    IconButton(onClick = { viewModel.clear(state.activeTarget) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear $editingLabel")
                    }
                }
            }

            SafeGoogleMap(
                center = state.activeLocation?.let { LatLng(it.lat, it.lng) } ?: DEFAULT_CENTER,
                markers = listOfNotNull(
                    state.work1?.let { LatLng(it.lat, it.lng) to "Work 1" },
                    state.work2?.let { LatLng(it.lat, it.lng) to "Work 2" },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onUnavailable = { message ->
                    MapUnavailableWithAction(
                        message = message,
                        actionLabel = "Open map in browser",
                        onAction = { viewModel.search() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                    )
                },
            )

            // Step 3: finish
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text("Skip")
                }
                androidx.compose.material3.Button(
                    onClick = { viewModel.save(onDone) },
                    enabled = state.canSave,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("Done")
                }
            }
        }
    }
}

/** At most this many geocoding candidates are offered; more is noise on a phone. */
private const val MAX_RESULTS = 5

/** One selectable search hit: name, then the full address for disambiguation. */
@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Place,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SlotPicker(
    selected: Int,
    work1: WorkLocation?,
    work2: WorkLocation?,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SlotCard(
            label = "Work 1",
            value = work1?.label,
            selected = selected == 0,
            onClick = { onSelect(0) },
            modifier = Modifier.weight(1f),
        )
        SlotCard(
            label = "Work 2",
            value = work2?.label,
            selected = selected == 1,
            onClick = { onSelect(1) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SlotCard(
    label: String,
    value: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (value != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = value ?: "Not set",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (value != null) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

private val DEFAULT_CENTER = LatLng(12.9716, 77.5946)