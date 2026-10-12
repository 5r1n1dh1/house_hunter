package com.househunter.app.ui.favourites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.househunter.app.data.prefs.PrefsRepository
import com.househunter.app.data.repository.ListingRepository
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.UserPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Joins persisted favourite ids against the live scrape.
 *
 * The repository's `find` is used per id rather than filtering `listings` once,
 * so an id saved before a locality switch still resolves from the current batch
 * rather than from a filtered subset the user is not looking at.
 */
@HiltViewModel
class FavouritesViewModel @Inject constructor(
    private val prefsRepository: PrefsRepository,
    private val listingRepository: ListingRepository,
) : ViewModel() {

    data class UiState(
        val listings: List<Listing> = emptyList(),
        val favouriteIds: Set<String> = emptySet(),
        val prefs: UserPrefs = UserPrefs(),
        val loading: Boolean = true,
    ) {
        /** Saved ids with no live listing behind them any more. */
        val missingCount: Int get() = favouriteIds.size - listings.size
    }

    val uiState: StateFlow<UiState> = combine(
        prefsRepository.favourites,
        listingRepository.listings,
        prefsRepository.prefs,
    ) { ids, listings, prefs ->
        UiState(
            listings = ids.mapNotNull { listingRepository.find(it) },
            favouriteIds = ids,
            prefs = prefs,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun toggle(id: String) {
        viewModelScope.launch { prefsRepository.toggleFavourite(id) }
    }

    fun clearAll() {
        viewModelScope.launch {
            uiState.value.favouriteIds.forEach { prefsRepository.toggleFavourite(it) }
        }
    }
}