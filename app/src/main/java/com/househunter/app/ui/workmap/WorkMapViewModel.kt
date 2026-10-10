package com.househunter.app.ui.workmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.househunter.app.data.prefs.PrefsRepository
import com.househunter.app.data.repository.Geocoder
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
        val results: List<WorkLocation> = emptyList(),
        val searching: Boolean = false,
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
        _ui.update { it.copy(activeTarget = index, results = emptyList(), query = "") }
    }

    fun onQueryChange(value: String) {
        _ui.update { it.copy(query = value) }
    }

    fun search() {
        val query = _ui.value.query.trim()
        if (query.length < 3) return
        viewModelScope.launch {
            _ui.update { it.copy(searching = true) }
            val location = geocoder.geocode(query)?.let { (lat, lng) ->
                WorkLocation(query, lat, lng)
            }
            _ui.update {
                it.copy(searching = false, results = listOfNotNull(location))
            }
        }
    }

    fun pick(location: WorkLocation) {
        val target = _ui.value.activeTarget
        _ui.update {
            if (target == 0) {
                it.copy(work1 = location, results = emptyList(), query = "")
            } else {
                it.copy(work2 = location, results = emptyList(), query = "")
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