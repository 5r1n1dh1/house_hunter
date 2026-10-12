package com.househunter.app.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.OsmPlace
import com.househunter.app.domain.model.Sort
import com.househunter.app.domain.usecase.ComputeDistance
import com.househunter.app.ui.components.EmptyState
import com.househunter.app.ui.components.ListingCard
import com.househunter.app.ui.theme.HouseBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenListing: (String) -> Unit,
    onOpenSites: () -> Unit,
    onOpenWorkMap: () -> Unit,
    onOpenFilters: () -> Unit,
    onOpenFavourites: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var sortMenuOpen by remember { mutableStateOf(false) }

    // Locality picker state
    var localitySheetOpen by remember { mutableStateOf(false) }

    /**
     * Contact the owner.
     *
     * The old button reported "no phone number on this listing" on every card
     * because none of the six sources publish a dialable number in search markup:
     * NoBroker gates it behind login, Razorent is chat-only, and a loose 10-digit
     * match on bengaluru.rent returns Google AdSense slot IDs. So when a phone is
     * genuinely present (some detail pages expose one) it dials; otherwise it
     * hands off to the listing, which carries the site's real contact route.
     */
    fun contactOwner(listing: Listing) {
        val phone = listing.phone?.takeIf { it.length in 10..13 }
        if (phone != null) {
            val dialed = runCatching {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            }.isSuccess
            if (dialed) return
        }
        val uri = runCatching { Uri.parse(listing.detailUrl) }.getOrNull()
        val opened = uri != null && runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.isSuccess
        if (!opened) {
            Toast.makeText(context, "Open the listing to contact the owner", Toast.LENGTH_SHORT).show()
        }
    }

    fun openInBrowser(listing: Listing) {
        val uri = runCatching { Uri.parse(listing.detailUrl) }.getOrNull() ?: return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("listing", listing.detailUrl))
            Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens the property in Google Maps.
     *
     * Previously the locate button simply navigated to the details screen, which
     * made the pin on a listing card useless. Three cases, in order of precision:
     * exact coordinates when the source publishes them (Razorent does), then a
     * locality search, then the listing's own web page. `google.navigation:q=`
     * is tried first so the Maps app launches in turn-by-turn navigation mode;
     * if no Maps app is installed the plain `geo:` URI opens whatever map app
     * exists, and failing that the listing page is copied to the clipboard.
     */
    fun openInMaps(listing: Listing) {
        val label = listing.locality.ifBlank { listing.title }
        if (!listing.hasRealLocation) {
            Toast.makeText(context, "This site does not publish an exact location", Toast.LENGTH_SHORT).show()
            return
        }
        // Only a source-published coordinate points at the property. A locality
        // centroid would drop a pin in the middle of the area, which is worse than
        // saying nothing, so the card's button is disabled in that case.
        val point = if (listing.hasRealLocation && listing.lat != null && listing.lng != null) {
            "${listing.lat},${listing.lng}"
        } else {
            null
        }
        val query = point ?: label
        val uri = if (point != null) {
            Uri.parse("google.navigation:q=$point&mode=d")
        } else {
            Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        }

        if (runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }.isSuccess) return

        val web = runCatching {
            Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}")
        }.getOrNull()
        if (web != null && runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, web))
            }.isSuccess
        ) return

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("location", query))
        Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
    }

    fun showPhotos(listing: Listing) {
        if (listing.images.isEmpty()) {
            Toast.makeText(context, "No photos available", Toast.LENGTH_SHORT).show()
        } else {
            onOpenListing(listing.id)
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("House Hunter") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = onOpenFavourites) {
                        Icon(Icons.Filled.Favorite, contentDescription = "Favourites")
                    }
                    IconButton(onClick = onOpenWorkMap) {
                        Icon(Icons.Filled.Work, contentDescription = "Work locations")
                    }
                    IconButton(onClick = onOpenSites) {
                        Icon(Icons.Filled.Search, contentDescription = "Sites")
                    }
                    IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            GradientHeader(
                locality = state.prefs.locality?.name ?: "Pick a locality",
                district = state.prefs.district?.name.orEmpty(),
                homeCount = state.listings.size,
                work1 = state.prefs.work1?.label,
                work2 = state.prefs.work2?.label,
                activeFilterCount = state.prefs.filters.activeCount,
                onLocalityClick = { localitySheetOpen = true },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    FilterChip(
                        selected = false,
                        onClick = { sortMenuOpen = true },
                        label = { Text(state.prefs.sort.label) },
                        colors = FilterChipDefaults.filterChipColors(),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = false,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                        ),
                    )
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        Sort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    viewModel.setSort(option)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                }

                FilterChip(
                    selected = state.prefs.filters.activeCount > 0,
                    onClick = onOpenFilters,
                    label = {
                        Text(
                            if (state.prefs.filters.activeCount > 0) {
                                "Filters (${state.prefs.filters.activeCount})"
                            } else {
                                "Filters"
                            },
                        )
                    },
                    leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(17.dp)) },
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = state.prefs.filters.activeCount > 0,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                )
            }

            when {
                state.listings.isEmpty() && state.refreshing -> EmptyState(
                    title = "Scanning sites",
                    subtitle = "Fetching live listings from your enabled sources.",
                )

                state.listings.isEmpty() && state.enabledSites == 0 -> EmptyState(
                    title = "No sites enabled",
                    subtitle = "Turn on a source or add your own site to start seeing homes.",
                    actionLabel = "Manage sites",
                    onAction = onOpenSites,
                )

                state.listings.isEmpty() -> EmptyState(
                    title = "No homes found",
                    subtitle = state.status.joinToString { s ->
                        if (s.error != null) "${s.name}: ${s.error}" else "${s.name}: ${s.count}"
                    }.ifBlank { "Try another locality or enable another site." },
                    actionLabel = "Manage sites",
                    onAction = onOpenSites,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.listings, key = { it.id }) { listing ->
                        ListingCard(
                            listing = listing,
                            workDistanceKm = ComputeDistance.distanceTo(listing, state.prefs.work1),
                            workDistanceKm2 = ComputeDistance.distanceTo(listing, state.prefs.work2),
                            isFavourite = listing.id in state.favouriteIds,
                            onToggleFavourite = { viewModel.toggleFavourite(listing.id) },
                            onOpenDetails = { onOpenListing(listing.id) },
                            onContact = { contactOwner(listing) },
                            onLocate = { openInMaps(listing) },
                            onPhotos = { showPhotos(listing) },
                            onOpenBrowser = { openInBrowser(listing) },
                        )
                    }
                }
            }
        }
    }

    if (localitySheetOpen) {
        LocalityPickerSheet(
            currentLocality = state.prefs.locality,
            currentDistrict = state.prefs.district,
            currentState = state.prefs.state,
            onDismiss = { localitySheetOpen = false },
            onSelect = { locality ->
                localitySheetOpen = false
                viewModel.setLocality(locality)
            },
            onBrowseAll = { district ->
                viewModel.browseLocalities(district)
            },
            onSearch = { query, districtName, stateName ->
                viewModel.searchLocalities(query, districtName, stateName)
            },
        )
    }
}

@Composable
private fun GradientHeader(
    locality: String,
    district: String,
    homeCount: Int,
    work1: String?,
    work2: String?,
    activeFilterCount: Int,
    onLocalityClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSystemInDarkTheme()) HouseBrush.headerDark else HouseBrush.header,
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = locality,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.clickable { onLocalityClick() },
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = "Change locality",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onLocalityClick() },
                )
            }
            if (district.isNotBlank()) {
                Text(
                    text = district,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.78f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HeaderStat("${homeCount}", "homes")
                work1?.let { HeaderStat(it.take(12), "Work 1") }
                work2?.let { HeaderStat(it.take(12), "Work 2") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalityPickerSheet(
    currentLocality: OsmPlace?,
    currentDistrict: OsmPlace?,
    currentState: OsmPlace?,
    onDismiss: () -> Unit,
    onSelect: (OsmPlace) -> Unit,
    onBrowseAll: suspend (OsmPlace) -> List<OsmPlace>,
    onSearch: suspend (String, String?, String?) -> List<OsmPlace>,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OsmPlace>>(emptyList()) }
    var browsed by remember { mutableStateOf<List<OsmPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var pendingSearch by remember { mutableStateOf("") }

    // Debounced search: triggers when the query changes and settles for 400ms.
    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.length < 3) {
            results = emptyList()
            searching = false
            pendingSearch = ""
            return@LaunchedEffect
        }
        searching = true
        pendingSearch = trimmed
        delay(400)
        // Only fire if the user hasn't typed anything else meanwhile.
        if (pendingSearch == trimmed) {
            results = onSearch(trimmed, currentDistrict?.name, currentState?.name)
            searching = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Switch locality",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            currentLocality?.let {
                Text(
                    "Current: ${it.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search locality") },
                supportingText = { Text("Type at least 3 letters, or browse all") },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = {
                        query.trim().takeIf { it.length >= 2 }?.let { typed ->
                            val place = OsmPlace(
                                osmId = typed.lowercase().hashCode().toLong(),
                                name = typed,
                                adminLevel = 8,
                            )
                            onSelect(place)
                        }
                    },
                    enabled = query.trim().length >= 2,
                ) {
                    Text("Use \"${query.trim().take(20)}\"")
                }
                TextButton(
                    onClick = {
                        currentDistrict?.let { district ->
                            browsing = true
                            scope.launch {
                                browsed = onBrowseAll(district)
                                browsing = false
                            }
                        }
                    },
                    enabled = currentDistrict != null && !browsing,
                ) {
                    Text(if (browsing) "Loading…" else "Browse all")
                }
            }

            if (searching || browsing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Text(
                        if (searching) "Searching…" else "Loading all localities…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            val shown = results.ifEmpty { browsed }
            if (shown.isEmpty() && !searching && !browsing && query.trim().length >= 3) {
                Text(
                    "No localities found for \"${query.trim()}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                items(shown, key = { "${it.osmId}:${it.name}" }) { place ->
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(place) }
                            .padding(horizontal = 4.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderStat(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.72f),
        )
    }
}