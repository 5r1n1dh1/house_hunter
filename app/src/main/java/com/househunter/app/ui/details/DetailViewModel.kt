package com.househunter.app.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.househunter.app.data.repository.ListingRepository
import com.househunter.app.domain.model.Listing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listingRepository: ListingRepository,
) : ViewModel() {

    private val listingId: String = savedStateHandle.get<String>("listingId").orEmpty()

    private val _listing = MutableStateFlow<Listing?>(listingRepository.find(listingId))
    val listing: StateFlow<Listing?> = _listing.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        viewModelScope.launch {
            _loading.value = true
            val detailed = runCatching { listingRepository.loadDetail(listingId) }.getOrNull()
            _listing.value = detailed ?: _listing.value
            _loading.value = false
        }
    }
}