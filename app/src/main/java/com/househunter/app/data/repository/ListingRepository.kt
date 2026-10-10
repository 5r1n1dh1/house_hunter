package com.househunter.app.data.repository

import com.househunter.app.data.scrape.Normalizer
import com.househunter.app.data.scrape.SiteRegistry
import com.househunter.app.data.scrape.SiteScraper
import com.househunter.app.di.IoDispatcher
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.SiteStatus
import com.househunter.app.domain.model.UserPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live data layer: scrapes every enabled site on demand and holds the results
 * **in memory only**. Nothing is persisted, so the app never shows stale data.
 */
@Singleton
class ListingRepository @Inject constructor(
    private val scraper: SiteScraper,
    private val registry: SiteRegistry,
    private val geocoder: Geocoder,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val _listings = MutableStateFlow<List<Listing>>(emptyList())
    val listings: StateFlow<List<Listing>> = _listings.asStateFlow()

    private val _status = MutableStateFlow<List<SiteStatus>>(emptyList())
    val status: StateFlow<List<SiteStatus>> = _status.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val byId = LinkedHashMap<String, Listing>()

    suspend fun refresh(prefs: UserPrefs): List<Listing> = withContext(io) {
        if (_refreshing.value) return@withContext _listings.value
        _refreshing.value = true

        val sites = registry.enabled()
        _status.value = sites.map { SiteStatus(it.id, it.name, loading = true) }

        val collected = mutableListOf<Listing>()
        sites.forEach { site ->
            val error = runCatching {
                val parsed = scraper.scrape(site, prefs)
                collected += parsed
                updateStatus(site.id) { it.copy(count = parsed.size, loading = false) }
                null
            }.exceptionOrNull()?.let { throwable ->
                updateStatus(site.id) { it.copy(loading = false, error = throwable.message ?: "fetch failed") }
                throwable.message ?: "fetch failed"
            }
        }

        val deduped = dedupe(collected)
        val located = attachFallbackCoordinates(deduped, prefs)

        synchronized(byId) {
            byId.clear()
            byId.putAll(located.associateBy { it.id })
        }
        _listings.value = located
        _refreshing.value = false
        located
    }

    fun find(id: String): Listing? = synchronized(byId) { byId[id] }

    /** Fetches the full detail page for a listing (called when details open). */
    suspend fun loadDetail(id: String): Listing? = withContext(io) {
        val current = find(id) ?: return@withContext null
        if (current.detailLoaded) return@withContext current
        val config = registry.find(current.siteId) ?: return@withContext current
        val detailed = scraper.fetchDetail(config, current)
        synchronized(byId) { byId[id] = detailed }
        _listings.value = _listings.value.map { if (it.id == id) detailed else it }
        detailed
    }

    private fun updateStatus(id: String, block: (SiteStatus) -> SiteStatus) {
        _status.value = _status.value.map { if (it.siteId == id) block(it) else it }
    }

    private fun dedupe(listings: List<Listing>): List<Listing> {
        val seenIds = mutableSetOf<String>()
        val seenFuzzy = mutableSetOf<String>()
        return listings.filter { listing ->
            val fuzzy = listOf(
                Normalizer.normalizeForDedupe(listing.title).take(40),
                listing.locality.lowercase(),
                listing.rent.toLong().toString(),
            ).joinToString("|")
            seenIds.add(listing.id) && seenFuzzy.add(fuzzy)
        }
    }

    /** Most sites expose no coordinates: fall back to the locality centroid. */
    private suspend fun attachFallbackCoordinates(listings: List<Listing>, prefs: UserPrefs): List<Listing> {
        val missing = listings.any { it.lat == null || it.lng == null }
        if (!missing) return listings
        val query = listOfNotNull(
            prefs.locality?.name,
            prefs.district?.name,
            prefs.state?.name,
        ).joinToString(", ").takeIf { it.isNotBlank() } ?: return listings
        val point = geocoder.geocode(query) ?: return listings
        return listings.map { listing ->
            if (listing.lat != null && listing.lng != null) listing
            else listing.copy(lat = point.first, lng = point.second)
        }
    }
}