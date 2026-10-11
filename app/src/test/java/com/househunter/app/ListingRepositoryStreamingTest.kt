package com.househunter.app

import com.househunter.app.data.remote.HttpFetcher
import com.househunter.app.data.remote.NominatimApi
import com.househunter.app.data.repository.Geocoder
import com.househunter.app.data.repository.ListingRepository
import com.househunter.app.data.scrape.SelectorSet
import com.househunter.app.data.scrape.SiteConfig
import com.househunter.app.data.scrape.SiteRegistry
import com.househunter.app.data.scrape.SiteScraper
import com.househunter.app.domain.model.Listing
import com.househunter.app.domain.model.OsmPlace
import com.househunter.app.domain.model.UserPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureTimeMillis

/**
 * Proves the concurrency contract of [ListingRepository.refresh].
 *
 * The repository used to scrape sources strictly one after another and publish one
 * list at the very end, so a user watched a spinner for the *sum* of every site's
 * rate limit before seeing anything, and one dead source aborted the whole run. It
 * now fans out with `async`/`awaitAll`, publishes each site's batch the moment it
 * lands, keeps batches per site so a re-scrape replaces rather than appends,
 * swallows per-site failures, and always clears `refreshing`.
 *
 * None of that is reachable from a unit test today, because two of the three
 * collaborators [ListingRepository] takes are **final** classes with no seam:
 *  - [SiteScraper] is `final` and `scrape` is not `open`, so a test cannot say
 *    "site A answers in 10ms, site B throws";
 *  - [SiteRegistry] is `final` *and* its only constructor demands a live `Context`
 *    plus a `PrefsRepository`. In a plain JVM unit test AGP's mockable `android.jar`
 *    throws from every `android.*` method - constructors included - and
 *    `testOptions.unitTests.returnDefaultValues` is not enabled in
 *    `app/build.gradle.kts`, so no `Context` can be built at all. The built-in site
 *    list also comes from an asset, which is out of reach for the same reason;
 *  - [Geocoder] needs no seam: [NominatimApi] is an interface, and the stub below
 *    keeps it off the network entirely.
 *
 * **Seam this file needs** (a production change, deliberately not made here):
 * make `SiteScraper` an `open class` with `open suspend fun scrape(...)`, and split
 * `SiteRegistry` into an interface (`suspend fun all()/enabled()/find()`) that the
 * asset-backed implementation provides and that `ListingRepository` depends on. The
 * doubles at the bottom of this file then compile unchanged.
 *
 * **Why `runBlocking` and not `runTest`:** `kotlinx-coroutines-test` is not on the
 * test classpath (`app/build.gradle.kts` declares only `libs.junit` and `libs.json`),
 * and adding a dependency is out of scope here. Virtual time would also be useless
 * for the wall-clock assertion below, which is measured against real delays.
 */
class ListingRepositoryStreamingTest {

    /**
     * A slow source must not hold back a fast one.
     *
     * This is the regression that motivated the rewrite: with a serial loop the only
     * `_listings` assignment happened after the last site, so a 10ms source stayed
     * invisible behind a 300ms one. The collector records each published snapshot
     * together with whether the slow site had already finished at that instant,
     * which makes "fast published while slow in flight" an ordering claim rather
     * than a coincidence.
     */
    @Test
    fun fastSiteIsPublishedWhileSlowSiteIsStillInFlight() = runBlocking {
        val scraper = ScriptedScraper { config, _ ->
            if (config.id == FAST_SITE) {
                Plan(delayMs = FAST_MS, listings = listOf(listing(FAST_SITE, "Fast 2 BHK", 20_000.0)))
            } else {
                Plan(delayMs = SLOW_MS, listings = listOf(listing(config.id, "Slow 3 BHK", 30_000.0)))
            }
        }
        val repo = repository(listOf(site(FAST_SITE), site(SLOW_SITE)), scraper)

        // `Dispatchers.Unconfined` so the collector resumes inside the very assignment
        // that published the value. A conflatable dispatcher could skip the intermediate
        // snapshot and let this test pass for the wrong reason.
        val observed = CopyOnWriteArrayList<Observation>()
        val collector = CoroutineScope(Dispatchers.Unconfined).launch {
            repo.listings.collect { snapshot ->
                observed += Observation(snapshot, slowFinished = scraper.hasFinished(SLOW_SITE))
            }
        }

        val published = repo.refresh(PREFS)
        collector.cancel()

        assertEquals("both batches must be present once the fan-out drains", 2, published.size)

        val early = observed.firstOrNull { observation ->
            !observation.slowFinished && observation.snapshot.any { it.siteId == FAST_SITE }
        }
        assertNotNull(
            "the fast site's listing was never published while the slow site was still in " +
                "flight; emissions seen: ${observed.map { it.snapshot.map(Listing::siteId) }}",
            early,
        )
        assertTrue(
            "the early emission must not already contain the slow site",
            early!!.snapshot.none { it.siteId == SLOW_SITE },
        )
    }

    /**
     * The fan-out is real concurrency, not interleaving on one thread.
     *
     * Serial scraping costs the *sum* of the per-site rate limits, which is what made
     * the app unusable. The bound is deliberately loose (75% of the serial cost) so a
     * loaded CI box does not flake - anything genuinely sequential measures at or
     * above 100%, so the margin still has teeth.
     */
    @Test
    fun sitesAreScrapedConcurrentlyRatherThanOneAfterAnother() {
        val sites = (1..TIMED_SITES).map { site("site$it") }
        val scraper = ScriptedScraper { config, _ ->
            Plan(
                delayMs = PER_SITE_MS,
                listings = listOf(listing(config.id, "${config.id} home", 12_000.0)),
            )
        }
        val repo = repository(sites, scraper)

        val elapsed = measureTimeMillis { runBlocking { repo.refresh(PREFS) } }
        val serial = sites.size * PER_SITE_MS
        assertTrue(
            "${sites.size} sites x ${PER_SITE_MS}ms took ${elapsed}ms, which is not " +
                "meaningfully below the serial cost of ${serial}ms",
            elapsed < serial * 0.75,
        )
    }

    /**
     * One dead source must not cancel the rest.
     *
     * `runCatching` sits *inside* each `async`, so sibling coroutines survive and the
     * failure surfaces as a per-site status rather than as an exception thrown out of
     * `refresh()`.
     */
    @Test
    fun oneFailingSiteDoesNotCancelTheOthers() = runBlocking {
        val healthy = listOf("alpha", "gamma")
        val scraper = ScriptedScraper { config, _ ->
            if (config.id == FAILING_SITE) {
                Plan(delayMs = FAST_MS, failWith = "boom exploded")
            } else {
                Plan(delayMs = FAST_MS, listings = listOf(listing(config.id, "${config.id} home", 15_000.0)))
            }
        }
        val repo = repository((healthy + FAILING_SITE).map { site(it) }, scraper)

        // Must return normally: an exception here would mean one source aborted the fan-out.
        val result = repo.refresh(PREFS)

        assertEquals(healthy.toSet(), result.map { it.siteId }.toSet())

        val statuses = repo.status.value.associateBy { it.siteId }
        assertEquals(healthy + FAILING_SITE, statuses.keys.toList())
        healthy.forEach { id ->
            assertEquals("healthy site $id must report its count", 1, statuses.getValue(id).count)
            assertNull("healthy site $id must not report an error", statuses.getValue(id).error)
            assertFalse("healthy site $id must stop loading", statuses.getValue(id).loading)
        }
        val failed = statuses.getValue(FAILING_SITE)
        assertEquals("boom exploded", failed.error)
        assertFalse("a failed site must stop loading", failed.loading)
    }

    /**
     * A re-scraped site replaces its batch instead of stacking a new copy on top.
     *
     * Batches are keyed by site id, so refresh N+1 sees exactly refresh N's output for
     * every site it touches. Appending would duplicate the whole list on every
     * pull-to-refresh, the classic symptom of a `_listings += ...` merge.
     */
    @Test
    fun refreshReplacesASitesPreviousBatchInsteadOfAppending() = runBlocking {
        val scraper = ScriptedScraper { config, call ->
            // "alpha" changes shape every round; "beta" is stable and must not be
            // duplicated by the second refresh either.
            val round = if (config.id == CHANGING_SITE) call + 1 else 1
            Plan(delayMs = FAST_MS, listings = listOf(listing(config.id, "${config.id} v$round", 11_000.0 * round)))
        }
        val repo = repository(listOf(site(CHANGING_SITE), site(STABLE_SITE)), scraper)

        repo.refresh(PREFS)
        val first = repo.listings.value
        repo.refresh(PREFS)
        val second = repo.listings.value

        assertEquals(2, first.size)
        assertEquals(setOf("$CHANGING_SITE v1", "$STABLE_SITE v1"), first.map { it.title }.toSet())
        assertEquals("the second refresh must replace batches, not append them", 2, second.size)
        assertEquals(setOf("$CHANGING_SITE v2", "$STABLE_SITE v1"), second.map { it.title }.toSet())
    }

    /**
     * `refreshing` must be cleared even when every site fails.
     *
     * This was a real bug: a throw left the flag stuck `true`, so the pull-to-refresh
     * spinner spun forever and every later refresh silently no-opped. Asserting the
     * flag alone would only prove half of it - the lock is released in the same
     * `finally`, so the second refresh here also proves the refresh really re-ran
     * instead of being rejected as a re-entrant call.
     */
    @Test
    fun refreshingIsClearedEvenWhenEverySiteFails() = runBlocking {
        val scraper = ScriptedScraper { _, _ -> Plan(delayMs = FAST_MS, failWith = "source down") }
        val repo = repository(listOf(site(FAILING_SITE)), scraper)

        repo.refresh(PREFS)
        assertFalse("a failed site must not leave `refreshing` stuck true", repo.refreshing.value)

        val callsBefore = scraper.totalScrapes()
        repo.refresh(PREFS)
        assertEquals(
            "a failed refresh must release the refresh lock, not just the flag",
            callsBefore + 1,
            scraper.totalScrapes(),
        )
        assertFalse(repo.refreshing.value)
    }

    /**
     * Two concurrent refreshes produce exactly one fan-out.
     *
     * The guard is `refreshLock.tryLock()`, not a check-then-set on the flag: two
     * callers racing on `_refreshing.value == false` would both start scraping every
     * site, doubling the request rate against rate-limited hosts. The rejected caller
     * gets whatever has been published so far.
     *
     * The second refresh is launched from *inside* the first fan-out, and the site
     * that launched it then blocks until that second call has returned. So the lock is
     * provably held when the second caller arrives, and the test cannot pass or fail
     * based on how loaded the machine is - which a `launch { }` racing a 300ms scrape
     * very much could.
     */
    @Test
    fun aSecondConcurrentRefreshIsRejectedInsteadOfStartingASecondFanOut() = runBlocking {
        val scope: CoroutineScope = this
        val sites = (1..REENTRANCY_SITES).map { site("site$it") }
        // A deferred, not an AtomicReference: `async` returns before its body runs, so the
        // handle to the concurrent call only exists once the scraper has launched it.
        val second = CompletableDeferred<Deferred<List<Listing>>>()
        val secondFinished = CompletableDeferred<Unit>()
        // The scraper and the repository reference each other; a holder breaks the
        // cycle without reaching for a lateinit local.
        val holder = AtomicReference<ListingRepository?>(null)

        val scraper = ScriptedScraper { config, call ->
            val launchesSecond = config.id == sites.first().id && call == 0
            if (launchesSecond) {
                second.complete(scope.async { holder.get()!!.refresh(PREFS) })
            }
            // Holds this site until the rejected caller is done, so the fan-out cannot
            // drain before the concurrent call has had its chance to arrive.
            val gate: (suspend () -> Unit)? = if (launchesSecond) ({ secondFinished.await() }) else null
            Plan(
                delayMs = SLOW_MS,
                listings = listOf(listing(config.id, "${config.id} home", 12_000.0)),
                beforeReturn = gate,
            )
        }
        val repo = repository(sites, scraper)
        holder.set(repo)

        val firstRefresh = async { repo.refresh(PREFS) }
        val secondResult = second.await().await()
        secondFinished.complete(Unit)
        val firstResult = firstRefresh.await()

        assertEquals(
            "a concurrent second refresh must not start a second fan-out",
            sites.size,
            scraper.totalScrapes(),
        )
        assertEquals(sites.size, firstResult.size)
        assertTrue(
            "the rejected caller receives the listings published so far",
            secondResult.size <= firstResult.size,
        )
        assertFalse(repo.refreshing.value)
    }

    private fun repository(
        sites: List<SiteConfig>,
        scraper: ScriptedScraper,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ListingRepository = ListingRepository(
        scraper = scraper,
        registry = FakeRegistry(sites),
        geocoder = Geocoder(StubNominatim, dispatcher),
        io = dispatcher,
    )

    /** What one site does on one call; resolved fresh per invocation by [ScriptedScraper]. */
    private data class Plan(
        val delayMs: Long = 0L,
        val listings: List<Listing> = emptyList(),
        /** When set, the site throws with this message instead of returning. */
        val failWith: String? = null,
        /** Awaited after the delay, before the batch is handed back: lets a test order itself. */
        val beforeReturn: (suspend () -> Unit)? = null,
    )

    /** One published snapshot, plus whether the slow site had already finished by then. */
    private data class Observation(
        val snapshot: List<Listing>,
        val slowFinished: Boolean,
    )

    /**
     * Scripted stand-in for [SiteScraper].
     *
     * Needs `SiteScraper` to be `open` with an `open scrape`. The real scraper's KDoc
     * already promises it "holds no mutable state between calls", which is exactly
     * the property this double has to model. Nothing here reaches the network:
     * `scrape` is fully overridden and `fetchDetail` is never called.
     */
    private class ScriptedScraper(
        private val plan: (SiteConfig, Int) -> Plan,
    ) : SiteScraper(HttpFetcher(OkHttpClient())) {

        private val lock = Any()
        private val calls = LinkedHashMap<String, Int>()
        private val finished = LinkedHashSet<String>()

        /** `callIndex` is per-site and zero-based, so a script can change its answer per round. */
        override suspend fun scrape(config: SiteConfig, prefs: UserPrefs): List<Listing> {
            val callIndex = synchronized(lock) {
                val next = calls.getOrElse(config.id) { 0 }
                calls[config.id] = next + 1
                next
            }

            val step = plan(config, callIndex)
            delay(step.delayMs)
            step.failWith?.let { throw IllegalStateException(it) }
            step.beforeReturn?.invoke()

            synchronized(lock) { finished += config.id }
            return step.listings
        }

        /** Total per-site scrape calls, i.e. the size of every fan-out. */
        fun totalScrapes(): Int = synchronized(lock) { calls.values.sum() }

        fun hasFinished(siteId: String): Boolean = synchronized(lock) { siteId in finished }
    }

    /**
     * Site-list stand-in for [SiteRegistry].
     *
     * `refresh` only reaches `enabled()`; `all()` and `find()` are overridden too so
     * the double honours the same contract if the repository switches to them.
     */
    private class FakeRegistry(private val sites: List<SiteConfig>) : SiteRegistry {
        override suspend fun all(): List<SiteConfig> = sites
        override suspend fun enabled(): List<SiteConfig> = sites
        override suspend fun find(id: String): SiteConfig? = sites.firstOrNull { it.id == id }
    }

    /**
     * Fails loudly if the test ever reaches Nominatim. The test listings have
     * empty localities so `enrichCoordinates` returns before geocoding, but this
     * guard catches accidental regressions where geocoding is attempted with
     * coordinates that fall through.
     */
    private object StubNominatim : NominatimApi {
        override suspend fun search(
            query: String,
            format: String,
            limit: Int,
            countryCodes: String,
            addressDetails: Int,
        ): ResponseBody {
            throw AssertionError("the test must never reach Nominatim: $query")
        }
    }

    private companion object {
        const val FAST_SITE = "fast"
        const val SLOW_SITE = "slow"
        const val FAILING_SITE = "boom"
        const val CHANGING_SITE = "alpha"
        const val STABLE_SITE = "beta"

        const val FAST_MS = 10L
        const val SLOW_MS = 300L

        /** Enough sites that a serial implementation is unambiguously over budget. */
        const val TIMED_SITES = 5
        const val PER_SITE_MS = 200L

        const val REENTRANCY_SITES = 3

        /** Distinct per-site titles keep the cross-site dedupe from eating the fixtures. */
        fun site(id: String): SiteConfig = SiteConfig(
            id = id,
            name = id,
            baseUrl = "https://$id.test",
            searchUrlTemplate = "https://$id.test/rent?page={PAGE}",
            selectors = SelectorSet(
                listContainer = "div.card",
                title = "h2.title",
                price = "span.price",
            ),
            enabled = true,
        )

        fun listing(siteId: String, title: String, rent: Double): Listing = Listing(
            id = "$siteId::$title",
            siteId = siteId,
            siteName = siteId,
            title = title,
            rent = rent,
            locality = "Testville",
            detailUrl = "https://$siteId.test/listing/$title",
        )

        /** Coordinates present => the geocoder is bypassed entirely. */
        val PREFS = UserPrefs(
            state = OsmPlace(1L, "Karnataka", 4),
            district = OsmPlace(2L, "Bengaluru", 6),
            locality = OsmPlace(3L, "Testville", 8, lat = 12.9716, lng = 77.5946),
        )
    }
}
