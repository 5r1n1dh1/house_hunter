package com.househunter.app.ui.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bed
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.model.LatLng
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.UserPrefs
import com.househunter.app.domain.usecase.ComputeDistance
import com.househunter.app.ui.components.EmptyState
import com.househunter.app.ui.components.ListingImage
import com.househunter.app.ui.components.ListingOwnerBadge
import com.househunter.app.ui.map.MapUnavailablePanel
import com.househunter.app.ui.map.SafeGoogleMap
import com.househunter.app.ui.components.PhotoStrip
import com.househunter.app.ui.components.Pill
import com.househunter.app.ui.components.formatArea
import com.househunter.app.ui.components.formatKm
import com.househunter.app.ui.components.formatRent
import com.househunter.app.ui.theme.HouseBrush

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    prefs: UserPrefs,
    onBack: () -> Unit,
    onSetWorkLocations: () -> Unit = {},
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(listing?.siteName ?: "Details") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
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
        bottomBar = {
            listing?.let { item ->
                Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Dials only when the listing really carries a number.
                        // None of the six sources publish one in search markup, so
                        // this usually hands off to the listing's own contact
                        // (chat, WhatsApp or a masked number) instead of toasting
                        // "no phone number" on every single property.
                        FilledTonalButton(
                            onClick = {
                                val phone = item.phone?.takeIf { it.length in 10..13 }
                                if (phone != null) {
                                    val dialed = runCatching {
                                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                                    }.isSuccess
                                    if (dialed) return@FilledTonalButton
                                }
                                openInBrowser(item)
                            },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                        ) {
                            Icon(
                                if (item.phone != null) Icons.Filled.Call else Icons.Filled.Chat,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (item.phone != null) "Call owner" else "Contact owner")
                        }
                        Button(
                            onClick = { openInBrowser(item) },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open listing")
                        }
                    }
                }
            }
        },
    ) { padding ->
        val item = listing
        if (item == null) {
            EmptyState(
                title = "Listing unavailable",
                subtitle = "This listing lived only in memory and was discarded. Refresh the list.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            HeroImage(item)

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Spacer(Modifier.height(14.dp))

                Text(
                    text = formatRent(item.rent),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.bhk?.let { Pill("${it} BHK", container = MaterialTheme.colorScheme.primaryContainer, content = MaterialTheme.colorScheme.onPrimaryContainer) }
                    formatArea(item.areaSqft)?.let { Pill(it) }
                    Pill(item.siteName, container = MaterialTheme.colorScheme.secondaryContainer, content = MaterialTheme.colorScheme.onSecondaryContainer)
                }

                Text(text = item.title, style = MaterialTheme.typography.bodyLarge)

                if (item.images.size > 1) {
                    PhotoStrip(
                        images = item.images,
                        detailUrl = item.detailUrl,
                        onImageClick = { },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                DistanceCard(
                    toWork1 = ComputeDistance.distanceTo(item, prefs.work1),
                    work1Label = prefs.work1?.label ?: "Work 1",
                    toWork2 = ComputeDistance.distanceTo(item, prefs.work2),
                    work2Label = prefs.work2?.label ?: "Work 2",
                    onClick = { showMap = true },
                    onSetWork = onSetWorkLocations,
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                FactGrid(item)

                item.amenities.takeIf { it.isNotEmpty() }?.let { amenities ->
                    SectionTitle("Amenities")
                    Text(
                        text = amenities.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                item.description?.takeIf { it.isNotBlank() }?.let { description ->
                    SectionTitle("About this home")
                    Text(text = description, style = MaterialTheme.typography.bodyMedium)
                }

                if (!item.ownerName.isNullOrBlank() || !item.ownerType.isNullOrBlank()) {
                    SectionTitle("Advertised by")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ListingOwnerBadge(item)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = item.ownerName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                item.phone?.let { phone ->
                    SectionTitle("Owner contact")
                    Text(text = phone, style = MaterialTheme.typography.titleMedium)
                }

                if (loading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text("Loading full details…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text(
                    text = item.detailUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // smart-cast is impossible across the `remember` boundary, so bind once:
    // an empty-map URL would otherwise throw on a recomposition.
    val mapListing = listing
    if (showMap && mapListing != null) {
        MapBottomSheet(
            listing = mapListing,
            prefs = prefs,
            onDismiss = { showMap = false },
        )
    }
}

@Composable
private fun HeroImage(item: Listing) {
    val image = item.images.firstOrNull()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clip(MaterialTheme.shapes.extraLarge),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            ListingImage(
                url = image,
                detailUrl = item.detailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.outline,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No photo available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DistanceCard(
    toWork1: Double?,
    work1Label: String,
    toWork2: Double?,
    work2Label: String,
    onClick: () -> Unit,
    onSetWork: () -> Unit,
) {
    // With no work location set, no distance can exist - prompt instead of showing
    // a dead "unavailable" line.
    if (toWork1 == null && toWork2 == null) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Add your work locations", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Once Work 1 and Work 2 are set, every home shows how far it is from both.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onSetWork, shape = MaterialTheme.shapes.large) {
                    Text("Set work locations")
                }
            }
        }
        return
    }

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .background(HouseBrush.price)
                .padding(18.dp),
        ) {
            Column {
                Text(
                    text = "DISTANCE TO WORK",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    DistanceStat(toWork1, work1Label)
                    DistanceStat(toWork2, work2Label)
                }
            }
        }
    }
}

@Composable
private fun DistanceStat(distanceKm: Double?, label: String) {
    Column {
        Text(
            text = formatKm(distanceKm),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun FactGrid(item: Listing) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FactCard(
            icon = Icons.Filled.Bed,
            label = "BHK",
            value = item.bhk?.toString() ?: "--",
            modifier = Modifier.weight(1f),
        )
        FactCard(
            icon = Icons.Filled.SquareFoot,
            label = "Area",
            value = formatArea(item.areaSqft) ?: "--",
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FactCard(
            icon = Icons.Filled.Place,
            label = "Locality",
            value = item.locality.ifBlank { "--" },
            modifier = Modifier.weight(1f),
        )
        FactCard(
            icon = Icons.Filled.PhotoCamera,
            label = "Photos",
            value = if (item.images.isEmpty()) "0" else item.images.size.toString(),
            modifier = Modifier.weight(1f),
        )
    }
    if (item.deposit != null || item.maintenance != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FactCard(
                icon = Icons.Filled.Home,
                label = "Deposit",
                value = item.deposit?.let { formatRent(it) } ?: "--",
                modifier = Modifier.weight(1f),
            )
            FactCard(
                icon = Icons.Filled.Home,
                label = "Maintenance",
                value = item.maintenance?.let { formatRent(it) } ?: "--",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FactCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        }
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
        // Crash-safe wrapper: a missing API key or Play services renders a panel
        // instead of taking the process down (the raw GoogleMap used to crash).
        SafeGoogleMap(
            center = target,
            markers = listOfNotNull(
                listing.lat?.let { LatLng(it, listing.lng ?: 0.0) to listing.title },
                prefs.work1?.let { LatLng(it.lat, it.lng) to it.label },
                prefs.work2?.let { LatLng(it.lat, it.lng) to it.label },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 380.dp),
            onUnavailable = { message ->
                MapUnavailablePanel(message, Modifier.padding(16.dp))
            },
        )
    }
}