package com.househunter.app.ui.preferences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.househunter.app.data.prefs.PrefsRepository
import com.househunter.app.data.repository.LocationRepository
import com.househunter.app.domain.model.OsmPlace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreferencesViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    private val prefsRepository: PrefsRepository,
) : ViewModel() {

    data class UiState(
        val states: List<OsmPlace> = emptyList(),
        val districts: List<OsmPlace> = emptyList(),
        val selectedState: OsmPlace? = null,
        val selectedDistrict: OsmPlace? = null,
        val selectedLocality: OsmPlace? = null,
        val loading: Boolean = false,
        val error: String? = null,
        // Locality type-ahead
        val query: String = "",
        val results: List<OsmPlace> = emptyList(),
        val searching: Boolean = false,
        val browsed: List<OsmPlace> = emptyList(),
        val browsing: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val prefs = prefsRepository.current()
            _ui.update {
                it.copy(
                    selectedState = prefs.state,
                    selectedDistrict = prefs.district,
                    selectedLocality = prefs.locality,
                )
            }
            // Bundled snapshot: instant, no network.
            _ui.update { it.copy(states = locationRepository.states()) }
            prefs.state?.let { state -> loadDistricts(state) }
        }
    }

    fun selectState(place: OsmPlace) {
        _ui.update {
            it.copy(
                selectedState = place,
                selectedDistrict = null,
                selectedLocality = null,
                districts = emptyList(),
                results = emptyList(),
                browsed = emptyList(),
                query = "",
                error = null,
            )
        }
        viewModelScope.launch { loadDistricts(place) }
    }

    private suspend fun loadDistricts(state: OsmPlace) {
        _ui.update { it.copy(loading = true) }
        val districts = locationRepository.districts(state)
        _ui.update {
            it.copy(
                districts = districts,
                loading = false,
                error = if (districts.isEmpty()) "No districts bundled for ${state.name}." else null,
            )
        }
    }

    fun selectDistrict(place: OsmPlace) {
        _ui.update {
            it.copy(
                selectedDistrict = place,
                selectedLocality = null,
                results = emptyList(),
                browsed = emptyList(),
                query = "",
                error = null,
            )
        }
    }

    fun selectLocality(place: OsmPlace) {
        _ui.update { it.copy(selectedLocality = place, query = "", results = emptyList()) }
    }

    /** Debounced Nominatim type-ahead (free, no key, ~1 s per request). */
    fun onQueryChange(value: String) {
        _ui.update { it.copy(query = value) }
        searchJob?.cancel()
        if (value.trim().length < 3) {
            _ui.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(400)
            _ui.update { it.copy(searching = true) }
            val results = locationRepository.searchLocalities(
                query = value,
                districtName = _ui.value.selectedDistrict?.name,
                stateName = _ui.value.selectedState?.name,
            )
            _ui.update { it.copy(results = results, searching = false) }
        }
    }

    /** Explicit "browse everything" - Overpass, slow by nature. */
    fun browseAll() {
        val district = _ui.value.selectedDistrict ?: return
        viewModelScope.launch {
            _ui.update { it.copy(browsing = true) }
            val places = locationRepository.browseLocalitiesOverpass(district)
            _ui.update { it.copy(browsed = places, browsing = false) }
        }
    }

    /** Manual entry: a stable synthetic id keeps the value usable downstream. */
    fun addManual(name: String) {
        val trimmed = name.trim()
        if (trimmed.length < 2) return
        val state = _ui.value
        val district = state.selectedDistrict
        selectLocality(
            OsmPlace(
                osmId = syntheticId(trimmed),
                name = trimmed,
                adminLevel = 8,
            ),
        )
        if (district != null) {
            _ui.update { it.copy(query = trimmed, results = emptyList(), browsed = emptyList()) }
        }
    }

    private fun syntheticId(name: String): Long = name.lowercase()
        .replace(Regex("[^a-z0-9]"), "")
        .fold(1125899906842597L) { acc, c -> acc * 31 + c.code }
        .let { if (it == 0L) 1L else it }

    fun save(onSaved: () -> Unit) {
        val state = _ui.value
        viewModelScope.launch {
            prefsRepository.setLocation(
                state = state.selectedState,
                district = state.selectedDistrict,
                locality = state.selectedLocality,
            )
            onSaved()
        }
    }

    val canSave: Boolean
        get() = _ui.value.selectedState != null && _ui.value.selectedLocality != null
}