package com.househunter.app.data.repository

import com.househunter.app.data.scrape.Normalizer
import com.househunter.app.data.scrape.SiteConfig
import com.househunter.app.data.scrape.SiteRegistry
import com.househunter.app.data.scrape.SiteScraper
import com.househunter.app.di.IoDispatcher
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.SiteStatus
import com.househunter.app.domain.model.UserPrefs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live data layer: scrapes every enabled site on demand and holds the results
 * **in memory only**. Nothing is persisted, so the app never shows stale data.
 *
 * **Concurrent and streaming.** Sources used to be scraped strictly one after
 * another and `_listings` was assigned exactly once, at the very end, so a user
 * waited ~64s (the sum of every site's rate limit x pages) staring at a spinner
 * and then saw all 70 listings at once. Now every enabled site is scraped
 * concurrently and each one publishes the moment it lands, so the first cards
 * appear after the quickest site (~5s) while the slowest is still fetching.
 *
 * This is safe to parallelise because the two shared pieces are already
 * concurrency-aware: `SiteScraper` holds no mutable state between calls (all
 * accumulators are call-local), and `HttpFetcher` throttles per host, so
 * different sites never serialise against each other's rate limit.
 *
 * Per-site results are kept in [bySite] rather than appended to one list, so a
 * site that is re-scraped has its previous batch *replaced* instead of
 * accumulating stale duplicates across refreshes.
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

    /** Latest batch per site; guarded by [mergeLock] because scrapes finish concurrently. */
    private val bySite = LinkedHashMap<String, List<Listing>>()
    private val mergeLock = Mutex()

    /** Guards re-entrancy: a second refresh while one is running is a no-op. */
    private val refreshLock = Mutex()

    /**
     * Runs area-geocode enrichment without blocking the refresh return.
     *
     * `coroutineScope` waits for **all** children, including `launch`, so an
     * in-scope `launch` still delayed `refresh()` by the geocoder's 1.1 s
     * per-request throttle and defeated streaming. A detached scope lets cards
     * appear first and coordinates arrive a moment later.
     */
    private val enrichmentScope = CoroutineScope(SupervisorJob() + io)

    suspend fun refresh(prefs: UserPrefs): List<Listing> = withContext(io) {
        // `tryLock`, not a `_refreshing` check-then-set: two concurrent callers
        // could both observe false and both start scraping every site.
        if (!refreshLock.tryLock()) return@withContext _listings.value
        _refreshing.value = true

        try {
            val sites = registry.enabled()
            _status.value = sites.map { SiteStatus(it.id, it.name, loading = true) }

            coroutineScope {
                sites.map { site ->
                    async {
                        runCatching { scraper.scrape(site, prefs) }
                            .onSuccess { parsed ->
                                publish(site, parsed)
                                updateStatus(site.id) {
                                    it.copy(count = parsed.size, loading = false)
                                }
                            }
                            .onFailure { throwable ->
                                // One dead source must never cancel the others,
                                // so this is swallowed here rather than propagated.
                                updateStatus(site.id) {
                                    it.copy(
                                        loading = false,
                                        error = throwable.message ?: "fetch failed",
                                    )
                                }
                            }
                    }
                }.awaitAll()
            }

            // Enrichment runs in the background, never blocking the refresh return.
            // The geocoder throttles to one request per second, so doing it inline
            // delayed every card behind a 1.1s sleep per locality and defeated
            // streaming entirely. Cards appear first, coordinates arrive a moment later.
            enrichmentScope.launch { enrichCoordinates(prefs) }

            _listings.value
        } finally {
            // Always clears: previously a throw from coordinate-fallback left
            // `_refreshing` stuck true and every later refresh silently no-opped.
            _refreshing.value = false
            refreshLock.unlock()
        }
    }

    /**
     * Merges one site's results and pushes them to observers immediately.
     *
     * Under [mergeLock] because scrapes complete concurrently; the critical
     * section is pure in-memory work so contention is negligible.
     */
    private suspend fun publish(
        site: SiteConfig,
        parsed: List<Listing>,
    ) {
        mergeLock.withLock {
            bySite[site.id] = parsed
            republishLocked()
        }
    }

    /**
     * Rebuilds the derived views of [bySite]. Caller must already hold [mergeLock].
     *
     * Every access to `byId` - here, in `find` and in `loadDetail` - takes this one
     * monitor. An earlier version guarded the writes here with `mergeLock` but the
     * reads with `synchronized(byId)`, and a main-thread `find` racing a rebuild
     * could observe the map mid-`clear`/`putAll`.
     */
    private fun republishLocked() {
        val merged = dedupe(bySite.values.flatten())
        synchronized(byId) {
            byId.clear()
            byId.putAll(merged.associateBy { it.id })
        }
        _listings.value = merged
    }

    fun find(id: String): Listing? = synchronized(byId) { byId[id] }

    /** Fetches the full detail page for a listing (called when details open). */
    suspend fun loadDetail(id: String): Listing? = withContext(io) {
        val current = find(id) ?: return@withContext null
        if (current.detailLoaded) return@withContext current
        val config = registry.find(current.siteId) ?: return@withContext current
        val detailed = scraper.fetchDetail(config, current)
        synchronized(byId) { byId[id] = detailed }
        // `update`, not `value = value.map{}`: the latter is a non-atomic
        // read-modify-write that loses a concurrent `publish` rebuild and reverts
        // this listing to its un-enriched copy.
        _listings.update { current -> current.map { if (it.id == id) detailed else it } }
        detailed
    }

    private fun updateStatus(id: String, block: (SiteStatus) -> SiteStatus) {
        // `update` is atomic; sites complete concurrently and a plain read-modify-
        // write dropped whichever update lost the race, stranding a row on
        // `loading = true` for the rest of the refresh.
        _status.update { current -> current.map { if (it.siteId == id) block(it) else it } }
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

    /**
     * Fills in missing coordinates **per distinct locality** and republishes.
     *
     * The previous version stamped one shared centroid onto every listing, so all
     * 70 cards reported identical distances and "locate on map" dropped a pin in
     * the middle of the city. Geocoding each distinct locality keeps distances
     * meaningful; results keep `hasRealLocation = false` so the UI marks them as
     * area approximations and refuses to use them for navigation.
     *
     * Bounded because Nominatim allows one request per second; past
     * [MAX_LOCALITY_GEOCODES] the remaining localities simply keep no coordinate.
     */
    private suspend fun enrichCoordinates(prefs: UserPrefs) {
        val batches = mergeLock.withLock { bySite.toMap() }
        val centroids = HashMap<String, Pair<Double, Double>>()
        val defaultPoint = defaultCentroid(prefs)

        // Keep the original spelling for the query; the lowercase key is only the
        // lookup key, so "Sahakara Nagar" is geocoded as written, not lowercased.
        val wanted = LinkedHashMap<String, String>()
        for (listing in batches.values.flatten()) {
            if (listing.hasRealLocation || (listing.lat != null && listing.lng != null)) continue
            val name = listing.locality.trim()
            if (name.isEmpty()) continue
            wanted.putIfAbsent(name.lowercase(), name)
            if (wanted.size >= MAX_LOCALITY_GEOCODES) break
        }
        if (wanted.isEmpty()) return

        for ((key, name) in wanted) {
            // Best-effort: a lookup failure leaves those listings without a
            // coordinate instead of failing the refresh.
            val point = runCatching { geocodeArea(name, prefs) }.getOrNull() ?: defaultPoint
            if (point != null) centroids[key] = point
        }
        if (centroids.isEmpty()) return

        mergeLock.withLock {
            bySite.replaceAll { _, batch ->
                batch.map { listing ->
                    if (listing.hasRealLocation || (listing.lat != null && listing.lng != null)) {
                        listing
                    } else {
                        val point = centroids[listing.locality.trim().lowercase()]
                        if (point == null) listing else listing.copy(lat = point.first, lng = point.second)
                    }
                }
            }
            republishLocked()
        }
    }

    /** Locality name plus its district/state context, which Nominatim needs to disambiguate. */
    private suspend fun geocodeArea(
        name: String,
        prefs: UserPrefs,
    ): Pair<Double, Double>? {
        val query = listOfNotNull(
            name.takeIf { it.isNotBlank() },
            prefs.district?.name,
            prefs.state?.name,
        ).joinToString(", ").takeIf { it.isNotBlank() } ?: return null
        return geocoder.geocode(query)
    }

    /** Last resort: the locality the user picked during onboarding. */
    private fun defaultCentroid(prefs: UserPrefs): Pair<Double, Double>? {
        val locality = prefs.locality
        return if (locality?.lat != null && locality.lng != null) locality.lat to locality.lng else null
    }
}

/** Nominatim allows one request per second; cap area geocodes per batch. */
private const val MAX_LOCALITY_GEOCODES = 6
