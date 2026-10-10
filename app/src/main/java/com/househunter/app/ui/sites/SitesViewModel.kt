package com.househunter.app.ui.sites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.househunter.app.data.prefs.PrefsRepository
import com.househunter.app.data.scrape.SiteRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SitesViewModel @Inject constructor(
    private val siteRegistry: SiteRegistry,
    private val prefsRepository: PrefsRepository,
) : ViewModel() {

    data class UiState(
        val sites: List<com.househunter.app.data.scrape.SiteConfig> = emptyList(),
        val loading: Boolean = true,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            val sites = siteRegistry.all()
            _ui.value = UiState(sites = sites, loading = false)
        }
    }

    fun toggle(id: String, enabled: Boolean) {
        viewModelScope.launch {
            prefsRepository.setSiteEnabled(id, enabled)
            load()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            prefsRepository.deleteSite(id)
            load()
        }
    }

    init {
        load()
    }
}