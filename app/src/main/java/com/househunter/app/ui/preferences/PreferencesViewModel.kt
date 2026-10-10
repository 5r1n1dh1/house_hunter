package com.househunter.app.ui.preferences

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.househunter.app.data.prefs.PrefsRepository
import com.househunter.app.data.repository.LocationRepository
import com.househunter.app.domain.model.OsmPlace
import dagger.hilt.android.lifecycle.HiltViewModel
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
        val localities: List<OsmPlace> = emptyList(),
        val selectedState: OsmPlace? = null,
        val selectedDistrict: OsmPlace? = null,
        val selectedLocality: OsmPlace? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val usingFallback: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

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
            loadStates()
            prefs.district?.let { loadDistricts(it, select = false) }
            prefs.locality?.let { loadLocalities(it, select = false) }
        }
    }

    private suspend fun loadStates() {
        _ui.update { it.copy(loading = true, error = null) }
        val states = locationRepository.states(forceReload = true)
        _ui.update {
            it.copy(
                states = states,
                loading = false,
                usingFallback = locationRepository.lastSource == "snapshot",
                error = if (states.isEmpty()) {
                    "Could not reach OpenStreetMap. Check your connection and tap Retry."
                } else {
                    null
                },
            )
        }
    }

    fun retry() {
        viewModelScope.launch { loadStates() }
    }

    fun selectState(place: OsmPlace) {
        _ui.update {
            it.copy(
                selectedState = place,
                selectedDistrict = null,
                selectedLocality = null,
                districts = emptyList(),
                localities = emptyList(),
                error = null,
            )
        }
        viewModelScope.launch { loadDistricts(place, select = true) }
    }

    private suspend fun loadDistricts(state: OsmPlace, select: Boolean) {
        _ui.update { it.copy(loading = true) }
        val districts = locationRepository.districts(state)
        _ui.update {
            it.copy(
                districts = districts,
                loading = false,
                usingFallback = it.usingFallback || locationRepository.lastSource == "snapshot",
                selectedDistrict = if (select) null else it.selectedDistrict,
                error = if (districts.isEmpty()) {
                    "Could not load districts for ${state.name}. Type the district below."
                } else {
                    null
                },
            )
        }
    }

    fun selectDistrict(place: OsmPlace) {
        _ui.update {
            it.copy(
                selectedDistrict = place,
                selectedLocality = null,
                localities = emptyList(),
                error = null,
            )
        }
        viewModelScope.launch { loadLocalities(place, select = true) }
    }

    private suspend fun loadLocalities(district: OsmPlace, select: Boolean) {
        _ui.update { it.copy(loading = true) }
        val localities = locationRepository.localities(district)
        _ui.update {
            it.copy(
                localities = localities,
                loading = false,
                selectedLocality = if (select) null else it.selectedLocality,
                error = if (localities.isEmpty()) {
                    "No localities from OpenStreetMap for ${district.name} — type it below."
                } else {
                    null
                },
            )
        }
    }

    fun selectLocality(place: OsmPlace) {
        _ui.update { it.copy(selectedLocality = place, error = null) }
    }

    /** Manual entry: a stable synthetic id keeps the value usable downstream. */
    fun addManual(name: String, target: Target) {
        val trimmed = name.trim()
        if (trimmed.length < 2) return
        val place = OsmPlace(syntheticId(trimmed), trimmed, when (target) {
            Target.STATE -> 4
            Target.DISTRICT -> 6
            Target.LOCALITY -> 8
        })
        when (target) {
            Target.STATE -> selectState(place)
            Target.DISTRICT -> selectDistrict(place)
            Target.LOCALITY -> selectLocality(place)
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

    enum class Target { STATE, DISTRICT, LOCALITY }

    val canSave: Boolean
        get() = _ui.value.selectedState != null && _ui.value.selectedLocality != null
}