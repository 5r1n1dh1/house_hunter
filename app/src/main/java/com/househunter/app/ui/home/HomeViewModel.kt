package com.househunter.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.househunter.app.data.prefs.PrefsRepository
import com.househunter.app.data.repository.ListingRepository
import com.househunter.app.data.scrape.SiteRegistry
import com.househunter.app.domain.model.Filters
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.SiteStatus
import com.househunter.app.domain.model.Sort
import com.househunter.app.domain.model.UserPrefs
import com.househunter.app.domain.usecase.ApplyFilters
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val listingRepository: ListingRepository,
    private val prefsRepository: PrefsRepository,
    private val siteRegistry: SiteRegistry,
) : ViewModel() {

    data class UiState(
        val prefs: UserPrefs = UserPrefs(),
        val listings: List<Listing> = emptyList(),
        val status: List<SiteStatus> = emptyList(),
        val refreshing: Boolean = false,
        val enabledSites: Int = 0,
        val error: String? = null,
    )

    private data class Meta(val enabledSites: Int = 0, val error: String? = null)

    private val enabledSiteCount = MutableStateFlow(0)
    private val error = MutableStateFlow<String?>(null)
    private val meta: StateFlow<Meta> = combine(enabledSiteCount, error) { count, err ->
        Meta(count, err)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Meta())

    val uiState: StateFlow<UiState> = combine(
        prefsRepository.prefs,
        listingRepository.listings,
        listingRepository.status,
        listingRepository.refreshing,
        meta,
    ) { prefs, listings, status, refreshing, info ->
        UiState(
            prefs = prefs,
            listings = ApplyFilters.apply(
                listings = listings,
                filters = prefs.filters,
                sort = prefs.sort,
                work1 = prefs.work1,
                work2 = prefs.work2,
            ),
            status = status,
            refreshing = refreshing,
            enabledSites = info.enabledSites,
            error = info.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            error.value = null
            runCatching {
                enabledSiteCount.value = siteRegistry.enabled().size
                listingRepository.refresh(prefsRepository.current())
            }.onFailure {
                error.value = it.message ?: "Refresh failed"
            }
        }
    }

    fun setSort(sort: Sort) {
        viewModelScope.launch { prefsRepository.setSort(sort) }
    }

    fun setFilters(filters: Filters) {
        viewModelScope.launch { prefsRepository.setFilters(filters) }
    }

    fun dismissError() {
        error.value = null
    }
}