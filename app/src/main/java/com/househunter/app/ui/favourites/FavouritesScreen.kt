package com.househunter.app.ui.favourites

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.usecase.ComputeDistance
import com.househunter.app.ui.components.EmptyState
import com.househunter.app.ui.components.ListingCard

/**
 * Saved properties.
 *
 * Only listing **ids** are persisted, so this screen joins those ids against the
 * current scrape rather than holding a stored copy. An entry whose listing has
 * left the result set is shown as a single explanatory row instead of silently
 * disappearing, so a favourite is never lost without explanation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavouritesScreen(
    onBack: () -> Unit,
    onOpenListing: (String) -> Unit,
    viewModel: FavouritesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Favourites") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            when {
                state.loading -> Unit

                state.favouriteIds.isEmpty() -> EmptyState(
                    title = "No favourites yet",
                    subtitle = "Tap the heart on any listing to save it here.",
                    modifier = Modifier.fillMaxSize(),
                )

                state.listings.isEmpty() -> EmptyState(
                    title = "${state.favouriteIds.size} saved, none live right now",
                    subtitle = "These listings are no longer in the current results. " +
                        "Refresh, or remove them to clear the list.",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "Remove all",
                    onAction = viewModel::clearAll,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                ) {
                    items(state.listings, key = { it.id }) { listing ->
                        ListingCard(
                            listing = listing,
                            workDistanceKm = ComputeDistance.distanceTo(listing, state.prefs.work1),
                            workDistanceKm2 = ComputeDistance.distanceTo(listing, state.prefs.work2),
                            isFavourite = true,
                            onToggleFavourite = { viewModel.toggle(listing.id) },
                            onOpenDetails = { onOpenListing(listing.id) },
                            onContact = { openListing(context, listing) },
                            onLocate = { openListing(context, listing) },
                            onPhotos = { openListing(context, listing) },
                            onOpenBrowser = { openListing(context, listing) },
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun openListing(context: android.content.Context, listing: Listing) {
    val uri = runCatching { android.net.Uri.parse(listing.detailUrl) }.getOrNull() ?: return
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, uri),
        )
    }
}