package com.househunter.app.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.househunter.app.domain.model.Sort
import com.househunter.app.ui.components.EmptyState
import com.househunter.app.ui.components.ListingCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenListing: (String) -> Unit,
    onOpenSites: () -> Unit,
    onOpenWorkMap: () -> Unit,
    onOpenFilters: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var sortMenuOpen by remember { mutableStateOf(false) }

    fun callOwner(listing: Listing) {
        val phone = listing.phone ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        }.onFailure {
            Toast.makeText(context, "No dialer available", Toast.LENGTH_SHORT).show()
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

    fun showPhotos(listing: Listing) {
        val images = listing.images
        if (images.isEmpty()) {
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
                title = {
                    Column {
                        Text(
                            text = state.prefs.locality?.name ?: "Home Hunter",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = buildString {
                                append(state.listings.size)
                                append(if (state.listings.size == 1) " home" else " homes")
                                val workCount = listOfNotNull(state.prefs.work1, state.prefs.work2).size
                                if (workCount > 0) {
                                    append(" · ")
                                    append(workCount)
                                    append(if (workCount == 1) " work location" else " work locations")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Row {
                        BoxedAction(
                            icon = Icons.Filled.Tune,
                            description = "Filters",
                            onClick = onOpenFilters,
                        )
                        BoxedAction(
                            icon = Icons.Filled.Search,
                            description = "Sites",
                            onClick = onOpenSites,
                        )
                        BoxedAction(
                            icon = Icons.Filled.Work,
                            description = "Work locations",
                            onClick = onOpenWorkMap,
                        )
                        IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Sort row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Box {
                    FilterChip(
                        selected = false,
                        onClick = { sortMenuOpen = true },
                        label = { Text(state.prefs.sort.label) },
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
            }

            when {
                state.listings.isEmpty() && state.refreshing -> EmptyState(
                    title = "Scanning sites...",
                    subtitle = "Fetching live listings, this can take a few seconds.",
                    modifier = Modifier.fillMaxSize(),
                )

                state.listings.isEmpty() && state.enabledSites == 0 -> EmptyState(
                    title = "No sites enabled",
                    subtitle = "Open the Sites screen to turn on a source or add your own.",
                    modifier = Modifier.fillMaxSize(),
                )

                state.listings.isEmpty() -> EmptyState(
                    title = "No homes found",
                    subtitle = state.status.joinToString { s ->
                        if (s.error != null) "${s.name}: ${s.error}" else "${s.name}: ${s.count}"
                    }.ifBlank { "Try another locality or site." },
                    modifier = Modifier.fillMaxSize(),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.listings, key = { it.id }) { listing ->
                        ListingCard(
                            listing = listing,
                            onOpenDetails = { onOpenListing(listing.id) },
                            onCall = { callOwner(listing) },
                            onLocate = { onOpenListing(listing.id) },
                            onPhotos = { showPhotos(listing) },
                            onOpenBrowser = { openInBrowser(listing) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxedAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description)
    }
}