package com.househunter.app.ui.addsite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.househunter.app.data.prefs.PrefsRepository
import com.househunter.app.data.scrape.SelectorSet
import com.househunter.app.data.scrape.SiteConfig
import com.househunter.app.data.scrape.SiteScraper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

@HiltViewModel
class AddSiteViewModel @Inject constructor(
    private val scraper: SiteScraper,
    private val prefsRepository: PrefsRepository,
) : ViewModel() {

    data class UiState(
        val name: String = "",
        val baseUrl: String = "",
        val searchUrlTemplate: String = "",
        val listContainer: String = "",
        val titleSelector: String = "",
        val priceSelector: String = "",
        val detailUrlSelector: String = "",
        val imageSelector: String = "",
        val maxPages: String = "1",
        val rateLimitSeconds: String = "6",
        val testing: Boolean = false,
        val preview: List<String> = emptyList(),
        val message: String? = null,
        val error: String? = null,
    ) {
        val valid: Boolean
            get() = name.isNotBlank() && baseUrl.isNotBlank() && searchUrlTemplate.contains("{LOCALITY}") &&
                listContainer.isNotBlank() && titleSelector.isNotBlank() &&
                priceSelector.isNotBlank() && detailUrlSelector.isNotBlank()
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun update(transform: (UiState) -> UiState) {
        _ui.update(transform)
    }

    fun testFetch() {
        val config = buildConfig(enabled = true) ?: return
        viewModelScope.launch {
            _ui.update { it.copy(testing = true, error = null, message = null) }
            val prefs = prefsRepository.current()
            val result = runCatching { scraper.scrape(config, prefs) }
            _ui.update { state ->
                result.fold(
                    onSuccess = { listings ->
                        state.copy(
                            testing = false,
                            preview = listings.take(3).map { "${it.title} — ₹${it.rent.toInt()}" }
                                .ifEmpty { listOf("Parsed ${listings.size} listings, none to preview") },
                            message = "Fetched ${listings.size} listings from ${config.baseUrl}",
                        )
                    },
                    onFailure = { error ->
                        state.copy(testing = false, error = error.message ?: "Fetch failed")
                    },
                )
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val config = buildConfig(enabled = true) ?: return
        viewModelScope.launch {
            prefsRepository.upsertSite(config)
            onSaved()
        }
    }

    private fun buildConfig(enabled: Boolean): SiteConfig? {
        val state = _ui.value
        if (!state.valid) {
            _ui.update {
                it.copy(
                    error = "Fill name, base URL, a search URL with {LOCALITY}, and the four required selectors.",
                )
            }
            return null
        }
        return SiteConfig(
            id = "custom-" + Random.nextInt(100_000, 999_999).toString() + "-" +
                state.name.lowercase().replace(Regex("[^a-z0-9]"), "").take(12),
            name = state.name.trim(),
            baseUrl = state.baseUrl.trim(),
            searchUrlTemplate = state.searchUrlTemplate.trim(),
            selectors = SelectorSet(
                listContainer = state.listContainer.trim(),
                title = state.titleSelector.trim(),
                price = state.priceSelector.trim(),
                imageUrl = state.imageSelector.trim().takeIf { it.isNotBlank() },
                detailUrl = state.detailUrlSelector.trim(),
            ),
            rateLimitSeconds = state.rateLimitSeconds.toIntOrNull()?.coerceIn(3, 60) ?: 6,
            enabled = enabled,
            isCustom = true,
        )
    }
}