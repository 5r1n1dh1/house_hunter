package com.househunter.app.ui.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.UserPrefs
import com.househunter.app.domain.usecase.ComputeDistance
import com.househunter.app.ui.components.EmptyState
import com.househunter.app.ui.components.PhotoStrip
import com.househunter.app.ui.components.formatArea
import com.househunter.app.ui.components.formatKm
import com.househunter.app.ui.components.formatRent
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
fun DetailScreen(
    prefs: UserPrefs,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val listing by viewModel.listing.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showMap by remember { mutableStateOf(false) }

    fun openInBrowser(item: Listing) {
        val uri = runCatching { Uri.parse(item.detailUrl) }.getOrNull() ?: return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("listing", item.detailUrl))
            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listing?.siteName ?: "Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    listing?.let { item ->
                        IconButton(onClick = { openInBrowser(item) }) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = "Open original listing")
                        }
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("listing", item.detailUrl))
                                Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                            },
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy link")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val item = listing
        if (item == null) {
            EmptyState(
                title = "Listing unavailable",
                subtitle = "This listing lived only in memory and was discarded. Refresh the list.",
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Distance highlight banner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val d1 = ComputeDistance.distanceTo(item, prefs.work1)
                val d2 = ComputeDistance.distanceTo(item, prefs.work2)
                if (d1 != null || d2 != null) {
                    Text(
                        text = listOfNotNull(
                            d1?.let { "${formatKm(it)} to ${prefs.work1?.label ?: "Work 1"}" },
                            d2?.let { "${formatKm(it)} to ${prefs.work2?.label ?: "Work 2"}" },
                        ).joinToString("   ·   "),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = "Distance unavailable (location not resolved)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = formatRent(item.rent),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            if (item.images.isNotEmpty()) {
                PhotoStrip(
                    images = item.images,
                    onImageClick = { },
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FactRow("BHK", item.bhk?.let { "$it" } ?: "--")
                FactRow("Area", formatArea(item.areaSqft) ?: "--")
                FactRow("Locality", item.locality.ifBlank { "--" })
                FactRow("District", item.district.ifBlank { "--" })
                item.deposit?.let { FactRow("Security deposit", formatRent(it)) }
                item.maintenance?.let { FactRow("Maintenance", formatRent(it)) }
                item.phone?.let { FactRow("Owner contact", it) }
            }

            if (item.amenities.isNotEmpty()) {
                Text(
                    text = "Amenities",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = item.amenities.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = "About this home",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (loading) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp))
                    Text("Loading full details…", style = MaterialTheme.typography.bodySmall)
                }
            }

            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        val phone = item.phone
                        if (phone == null) {
                            Toast.makeText(context, "No phone number on this listing", Toast.LENGTH_SHORT).show()
                        } else {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Call owner")
                }

                OutlinedButton(
                    onClick = { showMap = true },
                    modifier = Modifier.weight(1f),
                ) { Text("Locate") }
            }

            Button(
                onClick = { openInBrowser(item) },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
            ) {
                Icon(Icons.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Open original listing")
            }

            Text(
                text = item.detailUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (showMap && listing != null) {
        MapBottomSheet(
            listing = listing!!,
            prefs = prefs,
            onDismiss = { showMap = false },
        )
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapBottomSheet(
    listing: Listing,
    prefs: UserPrefs,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        val target = listing.lat?.let { LatLng(it, listing.lng ?: 0.0) }
            ?: prefs.work1?.let { LatLng(it.lat, it.lng) }
            ?: LatLng(12.9716, 77.5946)
        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(target, 13f)
        }
        GoogleMap(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isBuildingEnabled = false),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
        ) {
            Marker(state = MarkerState(target), title = listing.title)
            prefs.work1?.let { Marker(state = MarkerState(LatLng(it.lat, it.lng)), title = it.label) }
            prefs.work2?.let { Marker(state = MarkerState(LatLng(it.lat, it.lng)), title = it.label) }
        }
    }
}