package com.househunter.app.ui.workmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.househunter.app.data.prefs.PrefsRepository
import com.househunter.app.data.repository.Geocoder
import com.househunter.app.data.repository.PlaceSearch
import com.househunter.app.domain.model.WorkLocation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkMapViewModel @Inject constructor(
    private val prefsRepository: PrefsRepository,
    private val geocoder: Geocoder,
) : ViewModel() {

    data class UiState(
        val work1: WorkLocation? = null,
        val work2: WorkLocation? = null,
        val activeTarget: Int = 0,
        val query: String = "",
        /** Ranked geocoding candidates; empty until a search runs. */
        val results: List<PlaceSearch.Candidate> = emptyList(),
        val searching: Boolean = false,
        /** Set when a search returns nothing, so the UI can explain and offer a way in. */
        val notFound: Boolean = false,
        /** Manual "lat, lng" entry, for sites OpenStreetMap does not know. */
        val manualMode: Boolean = false,
    ) {
        val activeLocation: WorkLocation?
            get() = if (activeTarget == 0) work1 else work2

        val canSave: Boolean
            get() = work1 != null
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val prefs = prefsRepository.current()
            _ui.update { it.copy(work1 = prefs.work1, work2 = prefs.work2) }
        }
    }

    fun setTarget(index: Int) {
        _ui.update {
            it.copy(activeTarget = index, results = emptyList(), query = "", notFound = false, manualMode = false)
        }
    }

    fun onQueryChange(value: String) {
        _ui.update { it.copy(query = value, notFound = false) }
    }

    /**
     * Searches for candidates instead of resolving a single point, so a slightly
     * wrong top hit is still selectable. A miss sets [UiState.notFound] rather
     * than silently showing nothing, and never throws away the query.
     */
    fun search() {
        val query = _ui.value.query.trim()
        if (query.length < 3) return
        viewModelScope.launch {
            _ui.update { it.copy(searching = true, notFound = false) }
            val hits = geocoder.searchCandidates(query)
            _ui.update { it.copy(searching = false, results = hits, notFound = hits.isEmpty()) }
        }
    }

    fun pick(candidate: PlaceSearch.Candidate) {
        apply(candidate.toWorkLocation())
    }

    fun setManualMode(enabled: Boolean) {
        _ui.update { it.copy(manualMode = enabled) }
    }

    /**
     * Accepts a pasted coordinate pair (`12.9719, 77.6412`), including the
     * Google Maps URL form `!3d12.9719!4d77.6412`. This is the escape hatch for
     * private office parks that OpenStreetMap does not carry - without it a user
     * whose employer is unmappable simply cannot set a work location at all.
     */
    fun applyManual(text: String): Boolean {
        val cleaned = text.replace("!3d", " ").replace("!4d", " ")
            .replace(',', ' ')
            .replace(Regex("[^0-9.\\s-]"), " ")
            .trim()
        val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(cleaned).map { it.value }.toList()
        if (numbers.size < 2) return false
        val lat = numbers[0].toDoubleOrNull() ?: return false
        val lng = numbers[1].toDoubleOrNull() ?: return false
        // Reject obviously wrong input instead of centring the map in the ocean.
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return false

        val label = _ui.value.query.trim().ifEmpty { "Pinned location" }
        apply(WorkLocation(label, lat, lng))
        return true
    }

    private fun apply(location: WorkLocation) {
        val target = _ui.value.activeTarget
        _ui.update {
            if (target == 0) {
                it.copy(work1 = location, results = emptyList(), query = "", notFound = false)
            } else {
                it.copy(work2 = location, results = emptyList(), query = "", notFound = false)
            }
        }
    }

    fun clear(target: Int) {
        _ui.update {
            if (target == 0) it.copy(work1 = null) else it.copy(work2 = null)
        }
    }

    fun save(onSaved: () -> Unit) {
        val state = _ui.value
        viewModelScope.launch {
            prefsRepository.setWork(0, state.work1)
            prefsRepository.setWork(1, state.work2)
            onSaved()
        }
    }
}